import re

with open('tramai-sovereign/src/test/kotlin/dev/tramai/sovereign/evidence/SovereignEvidencePackWriterTest.kt', 'r') as f:
    content = f.read()

def extract_value(lines, start_idx):
    """Extract the value for a parameter that may span multiple lines (nested parens)."""
    line = lines[start_idx]
    # Find the = sign
    eq_idx = line.find('=')
    raw = line[eq_idx+1:].strip()
    
    if raw.startswith('(') or raw.startswith('{') or raw.startswith('['):
        # Need to find matching close
        bracket = raw[0]
        close_bracket = {'(': ')', '{': '}', '[': ']'}[bracket]
        depth = 1
        result = raw
        i = start_idx
        line_offset = 0
        while depth > 0:
            for j, ch in enumerate(result):
                if ch == bracket:
                    depth += 1
                elif ch == close_bracket:
                    depth -= 1
                    if depth == 0:
                        # Extract just this value
                        return result[:j+1]  # includes trailing comma potentially
            # Need next line
            i += 1
            if i >= len(lines):
                break
            line_offset += 1
            result += '\n' + lines[i]
        # Whole thing
        while start_idx + line_offset + 1 < len(lines) and not lines[start_idx + line_offset + 1].strip().endswith(','):
            line_offset += 1
            result += '\n' + lines[start_idx + line_offset]
        return result.rstrip(',')
    
    if raw.endswith(','):
        return raw[:-1]
    return raw


def find_matching_paren(text, start):
    depth = 1
    i = start + 1
    while i < len(text) and depth > 0:
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
        i += 1
    return i - 1

pattern = 'SovereignEvidencePackGenerator\\.generate\\('
matches = list(re.finditer(pattern, content))
print(f'Found {len(matches)} calls')

for match in reversed(matches):
    start = match.start() + len('SovereignEvidencePackGenerator.generate(')
    end = find_matching_paren(content, start)
    
    inner = content[start:end]
    lines = inner.split('\n')
    
    # Find all parameter names and their values
    params = {}
    param_order = []
    
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        
        # Check if this line starts a parameter
        param_match = re.match(r'\s*(\w+)\s*=', stripped)
        if param_match:
            name = param_match.group(1)
            param_order.append(name)
            
            # Extract value (may span multiple lines)
            eq_idx = line.find('=')
            first_part = line[eq_idx+1:].strip()
            
            # Collect lines until we have a complete expression
            value_lines = [first_part]
            depth = 0
            in_string = False
            escape = False
            
            # Track bracket depth in the first part
            for ch in first_part:
                if escape:
                    escape = False
                    continue
                if ch == '\\':
                    escape = True
                elif ch == '"':
                    in_string = not in_string
                elif not in_string:
                    if ch in '({[':
                        depth += 1
                    elif ch in ')}]':
                        depth -= 1
            
            j = i + 1
            loc_depth = depth
            while j < len(lines) and (loc_depth > 0 or not value_lines[-1].rstrip(',').strip().endswith(',') and not value_lines[-1].endswith(',')):
                # Actually we need to find when the value is complete
                # A value is complete when we hit a line that starts a new param
                next_line = lines[j].strip()
                next_param = re.match(r'(\w+)\s*=', next_line)
                if next_param and loc_depth <= 0 and value_lines[-1].rstrip(',').strip().endswith(','):
                    break
                
                value_lines.append(lines[j])
                for ch in lines[j]:
                    if escape:
                        escape = False
                        continue
                    if ch == '\\':
                        escape = True
                    elif ch == '"':
                        in_string = not in_string
                    elif not in_string:
                        if ch in '({[':
                            loc_depth += 1
                        elif ch in ')}]':
                            loc_depth -= 1
                
                j += 1
                i = j - 1  # Update outer loop counter
            
            full_value = '\n'.join(value_lines)
            # Strip trailing comma
            full_value = full_value.rstrip(',').rstrip()
            params[name] = full_value
        i += 1
    
    # Separate into core, verification, optional
    core_names = ['deploymentMode', 'allowedModels', 'allowedProviders', 'providerZones']
    optional_names = ['zeroEgress', 'auditChain', 'supplyChain', 'releaseBundle', 'attestation']
    
    core = {k: v for k, v in params.items() if k in core_names}
    vs = params.get('verificationSettings')
    vr = params.get('verificationReceipts')
    optional = {k: v for k, v in params.items() if k in optional_names}
    
    # Build new content
    indent = '        '
    
    lines_new = []
    lines_new.append(f'{indent}SovereignEvidencePackGenerator.GenerationParams(')
    
    for name in core_names:
        if name in core:
            lines_new.append(f'{indent}    {name} = {core[name]},')
    
    lines_new.append(f'{indent}    verification = SovereignEvidencePackGenerator.VerificationEvidence(')
    if vs:
        lines_new.append(f'{indent}        verificationSettings = {vs},')
    if vr:
        lines_new.append(f'{indent}        verificationReceipts = {vr},')
    lines_new.append(f'{indent}    ),')
    
    if optional:
        lines_new.append(f'{indent}    optionalEvidence = SovereignEvidencePackGenerator.OptionalEvidence(')
        for name in optional_names:
            if name in optional:
                lines_new.append(f'{indent}        {name} = {optional[name]},')
        lines_new.append(f'{indent}    ),')
    
    lines_new.append(f'{indent})')
    
    new_inner = '\n'.join(lines_new)
    
    content = content[:start] + '\n' + new_inner + content[end:]

with open('tramai-sovereign/src/test/kotlin/dev/tramai/sovereign/evidence/SovereignEvidencePackWriterTest.kt', 'w') as f:
    f.write(content)

print('Done - file updated')
