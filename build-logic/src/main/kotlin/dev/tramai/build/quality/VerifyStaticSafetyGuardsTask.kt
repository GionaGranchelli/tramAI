package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File

data class SafetyFinding(val rule: String, val path: String, val line: Int, val symbol: String, val snippet: String, val exempt: Boolean = false)

@CacheableTask
abstract class VerifyStaticSafetyGuardsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val configFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceFiles: ConfigurableFileCollection
    @get:Internal abstract val repositoryRoot: Property<String>
    @get:OutputDirectory abstract val reportsDir: org.gradle.api.file.DirectoryProperty

    @TaskAction fun verify() {
        val root = File(repositoryRoot.get()); val config = StaticSafetyGuardConfigParser.parse(configFile.get().asFile.readText(), root)
        val exemptions = config.exemptions.associateBy { Triple(it.rule, it.path, it.symbol) }.toMutableMap(); val used = mutableSetOf<Triple<String,String,String>>()
        val findings = sourceFiles.files.filter { it.isFile }.flatMap { scan(it, root, config) }.map { f ->
            val key = Triple(f.rule, f.path, f.symbol); val hit = exemptions[key] != null; if (hit) used += key; f.copy(exempt = hit)
        }.sortedWith(compareBy({it.path},{it.line},{it.rule},{it.symbol}))
        val stale = exemptions.keys - used
        val dir = reportsDir.get().asFile.apply { mkdirs() }
        dir.resolve("findings.txt").writeText(findings.joinToString("\n") { format(it) } + if (findings.isEmpty()) "" else "\n")
        val unexplained = findings.filterNot { it.exempt }
        dir.resolve("summary.txt").writeText("Static safety guards\nfindings: ${findings.size}\nunexplained: ${unexplained.size}\nexemptions live: ${used.size}\nstale exemptions: ${stale.size}\n" + config.rules.joinToString("\n") { r -> "${r.id}: ${findings.count { it.rule == r.id }}" })
        if (stale.isNotEmpty() || unexplained.isNotEmpty()) throw GradleException(buildString { unexplained.forEach { appendLine(format(it)) }; stale.forEach { appendLine("stale exemption: ${it.first} | ${it.second} | ${it.third}") }; append("Fix the code or add a scoped exemption with rationale to config/quality/static-safety-guards.yml") })
        logger.lifecycle("static-safety-guards: ${findings.size} findings, ${used.size} live exemptions, 0 unexplained")
    }
    private fun format(f: SafetyFinding) = "${f.rule} | ${f.path} | ${f.line} | ${f.symbol} | ${if (f.exempt) "(exempt) " else ""}${f.snippet}"
    private fun scan(file: File, root: File, config: StaticSafetyGuardConfig): List<SafetyFinding> {
        val path = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/'); val tokens = Lexer(file.readText()).lex(); val out = mutableListOf<SafetyFinding>()
        fun approved(rule: StaticSafetyRule) = rule.approvedPaths.any { path.startsWith(it.trimEnd('/') + "/") || path == it.trimEnd('/') }
        for ((i,t) in tokens.withIndex()) {
            if (t.kind != Kind.ID || i+1 >= tokens.size || tokens[i+1].text != "(") continue
            val q = qualified(tokens, i); val simple=t.text
            config.rules.forEach { r -> if (!approved(r)) {
                // An approved owning factory also owns nested lifecycle arguments
                // (for example CoroutineScope(SupervisorJob())). This keeps the
                // exemption keyed to the explicit outer construction site.
                val nestedOwned = isNestedOwned(r, path, i, tokens, config.exemptions)
                val matched = when (r.match) { "call-name" -> callMatches(simple,q,r.symbols) || (simple in r.receiverOrCall && i+1<tokens.size && (tokens[i+1].text=="(" || (i+2<tokens.size && tokens[i+1].text==".")))
                    "multi" -> callMatches(simple,q,r.symbols)
                    "receiver-call" -> receiverMatch(tokens,i,q,r) && argHas(tokens,i,r.sensitiveSymbols)
                    else -> false }
                if (matched && !nestedOwned) out += SafetyFinding(r.id,path,t.line,matchedSymbol(simple,q,r),snippet(file,t.line))
                if ((r.match=="body-use-block" || r.match=="multi") && simple=="use" && bodyReceiver(tokens,i) && blockHas(tokens,i,r.blockReadSymbols)) out += SafetyFinding(r.id,path,blockLine(tokens,i,r.blockReadSymbols),r.blockReadSymbols.first { blockContains(tokens,i,it) },snippet(file,blockLine(tokens,i,r.blockReadSymbols)))
            }}
        }; return out.distinctBy { Triple(it.rule,it.line,it.symbol) }
    }
    private fun matchedSymbol(s:String,q:String,r:StaticSafetyRule)=r.symbols.firstOrNull { callMatches(s,q,listOf(it)) } ?: s
    private fun isNestedOwned(r:StaticSafetyRule,path:String,i:Int,ts:List<Tok>,exemptions:List<StaticSafetyExemption>):Boolean {
        val owned = exemptions.filter { it.rule==r.id && it.path==path }
        for (j in 0 until i) if (ts[j].kind==Kind.ID && j+1<ts.size && ts[j+1].text=="(") {
            val q=qualified(ts,j); val symbol=r.symbols.firstOrNull { callMatches(ts[j].text,q,listOf(it)) } ?: continue
            if (owned.none { it.symbol==symbol }) continue
            if (i < balanced(ts,j+1,"(",")")) return true
        }
        return false
    }
    private fun callMatches(s:String,q:String,syms:List<String>)=syms.any { (if(it.endsWith("*")) q.startsWith(it.dropLast(1)) else if(it.contains('.')) q==it else s==it || (it=="GlobalScope" && q.startsWith("GlobalScope."))) }
    private fun qualified(ts:List<Tok>,i:Int):String { var j=i; while(j>=2 && ts[j-1].text=="." && ts[j-2].kind==Kind.ID) j-=2; return ts.subList(j,i+1).joinToString("") { it.text } }
    private fun receiverMatch(ts:List<Tok>,i:Int,q:String,r:StaticSafetyRule):Boolean { val rec=q.substringBeforeLast('.',""); return rec in r.receivers || rec.endsWith("Logger") }
    private fun argHas(ts:List<Tok>,i:Int,syms:List<String>):Boolean { val end=balanced(ts,i+1,"(",")")
        return (i+2 until end).any { n -> ts[n].kind==Kind.ID && (ts[n].text in syms || ("document.content" in syms && ts[n].text=="content" && ts.getOrNull(n-1)?.text=="." && ts.getOrNull(n-2)?.text=="document")) }
    }
    private fun bodyReceiver(ts:List<Tok>,i:Int)=i>=4 && ts[i-1].text=="." && ts[i-2].text==")" && ts[i-3].text=="(" && ts[i-4].text=="body"
    private fun blockHas(ts:List<Tok>,i:Int,syms:List<String>)=blockContains(ts,i,syms.firstOrNull() ?: "") || syms.any { s -> blockContains(ts,i,s) }
    private fun blockContains(ts:List<Tok>,i:Int,s:String):Boolean { val open=ts.indexOfFirstFrom(i+1){it.text=="{"}; if(open<0)return false; val end=balanced(ts,open,"{","}"); return ts.subList(open+1,end).zipWithNext().any { it.first.text==s && it.second.text=="(" } }
    private fun blockLine(ts:List<Tok>,i:Int,syms:List<String>)=ts.withIndex().firstOrNull { (n,t) -> t.text in syms && ts.getOrNull(n+1)?.text=="(" && n>i }?.value?.line ?: ts[i].line
    private fun List<Tok>.indexOfFirstFrom(start:Int,p:(Tok)->Boolean)=indices.firstOrNull { it>=start && p(this[it]) } ?: -1
    private fun balanced(ts:List<Tok>,start:Int,a:String,b:String):Int { var d=0; for(j in start until ts.size){if(ts[j].text==a)d++;if(ts[j].text==b){d--;if(d==0)return j}};return ts.size}
    private fun snippet(f:File,line:Int)=f.readLines().getOrNull(line-1)?.trim()?.take(240) ?: ""
}

private enum class Kind { ID, PUNCT }
private data class Tok(val text:String,val line:Int,val kind:Kind)
private class Lexer(private val s:String) {
    fun lex():List<Tok> {
        val out=mutableListOf<Tok>(); var i=0; var line=1; var block=0
        fun id(c:Char)=c.isLetterOrDigit()||c=='_'||c=='$'
        while(i<s.length) {
            val c=s[i]
            if(block>0){ when { i+1<s.length&&s.startsWith("/*",i)->{block++;i+=2}; i+1<s.length&&s.startsWith("*/",i)->{block--;i+=2}; else->{if(c=='\n')line++;i++} }; continue }
            if(i+1<s.length&&s.startsWith("//",i)){i+=2;while(i<s.length&&s[i]!='\n')i++;continue}
            if(i+1<s.length&&s.startsWith("/*",i)){block=1;i+=2;continue}
            if(s.startsWith("\"\"\"",i)){i+=3;while(i<s.length&&!s.startsWith("\"\"\"",i)){if(s[i]=='\n')line++;i++};i+=3;continue}
            if(c=='\"'||c=='\''){val q=c;i++;while(i<s.length&&s[i]!=q){if(s[i]=='\\')i+=2 else {if(s[i]=='\n')line++;i++}};i++;continue}
            if(id(c)){val l=line;val st=i;i++;while(i<s.length&&id(s[i]))i++;out+=Tok(s.substring(st,i),l,Kind.ID);continue}
            if(c in ".(){}")out+=Tok(c.toString(),line,Kind.PUNCT);if(c=='\n')line++;i++
        }; return out
    }
}
