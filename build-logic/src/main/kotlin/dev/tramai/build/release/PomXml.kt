package dev.tramai.build.release

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** XML helpers moved from the historical root build script (9.2b extraction). */
object PomXml {
    fun parse(file: File): Element {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance()
        documentBuilderFactory.isNamespaceAware = false
        return documentBuilderFactory.newDocumentBuilder().parse(file).documentElement
    }
}

fun Element.directChild(name: String): Element? {
    val children = childNodes
    for (index in 0 until children.length) {
        val node = children.item(index)
        if (node is Element && node.tagName == name) {
            return node
        }
    }
    return null
}

fun Element.directChildText(name: String): String? = directChild(name)?.textContent?.trim()
