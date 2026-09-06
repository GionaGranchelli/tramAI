# TramAI 0.7.0 — Document Metadata Classification

> **Status:** Draft roadmap slice  
> **Target release:** TramAI 0.7.0  
> **Relationship:** Complements `ROADMAP-0.7.0.md`, especially Classification Pipeline 2.0 and policy-aware provider/model selection  
> **Scope:** Read classification information already present in document metadata and feed it into TramAI's existing classification and routing mechanisms.  

---

## 1. Decision

TramAI 0.7.0 should support a deliberately small first step for document classification:

> **If a supported document contains an explicit classification label in its metadata, TramAI can read that label, map it to a `DataClassification`, and use the resulting classification before provider/model routing.**

This slice does **not** attempt to infer sensitivity from arbitrary document content.

The intended flow is:

```text
document
   ↓
read metadata locally
   ↓
recognized classification label?
   ├─ no  → no classification inferred by this feature
   └─ yes
        ↓
map external label to TramAI DataClassification
        ↓
ClassifiedDocument(..., source = DECLARED)
        ↓
classification-aware policy / provider eligibility
        ↓
selected eligible route
```

The document payload must not be sent to a model merely to discover metadata that can be inspected locally.

---

## 2. Why

Organizations frequently label documents with classifications such as:

```text
PUBLIC
INTERNAL
CONFIDENTIAL
SECRET
```

or equivalent organization-specific names.

When that classification is already present as document metadata, asking an LLM to rediscover it is unnecessary and weakens the trust boundary. TramAI should treat the label as input to the existing governance path instead.

This directly supports the 0.7.0 routing invariant:

```text
effective classification
        ↓
provider/model eligibility
        ↓
selection only from eligible candidates
```

Example:

```text
metadata label = SECRET
        ↓
configured mapping = RESTRICTED
        ↓
cloud provider not eligible
        ↓
local/approved route selected, or execution denied if none is eligible
```

---

## 3. Minimal Contract

### 3.1 Metadata reader

Introduce a small document-metadata classification boundary capable of inspecting supported document formats without invoking an AI provider.

The reader should return either:

```text
recognized external classification label
```

or:

```text
no recognized classification metadata
```

It should not classify document body text in this 0.7.0 slice.

### 3.2 Mapping

External labels should map into TramAI's canonical `DataClassification` values rather than changing the core enum for every organization taxonomy.

Example configuration:

```yaml
tramai:
  classification:
    document-metadata:
      labels:
        PUBLIC: PUBLIC
        INTERNAL: INTERNAL
        CONFIDENTIAL: CONFIDENTIAL
        SECRET: RESTRICTED
```

Matching rules and exact configuration shape are implementation details, but mappings must be explicit and deterministic.

### 3.3 Provenance

A successfully read document classification should preserve provenance.

For an explicit document label, the resulting classification should normally enter the existing model as:

```text
ClassificationSource.DECLARED
```

The original external label may be retained as safe governance metadata where appropriate, but document contents must not be copied into classification telemetry.

### 3.4 Routing order

Metadata classification must happen before provider/model eligibility is finalized for the document-bearing request.

Required ordering:

```text
inspect metadata
    ↓
resolve classification
    ↓
evaluate policy / eligible providers and models
    ↓
only then expose document content to the selected route
```

---

## 4. Failure and Default Semantics

The absence of recognized classification metadata must **not** mean `PUBLIC`.

These cases are distinct:

```text
explicit PUBLIC label
metadata present but label unknown
no classification metadata
metadata unreadable / unsupported format
```

0.7.0 must preserve that distinction so application or policy configuration can decide the fallback behavior.

A metadata parser failure must not silently downgrade a document to a less restrictive classification.

If an external label is recognized but has no configured mapping, the safe behavior is fail-loud or policy-defined handling rather than silently treating it as `PUBLIC`.

---

## 5. Tasks

1. Define a small document metadata classification SPI/value model.
2. Define supported initial document formats based on reliable metadata access rather than broad format count.
3. Add deterministic external-label → `DataClassification` mapping.
4. Preserve classification provenance as `DECLARED` for explicit document labels.
5. Integrate metadata classification before provider/model eligibility for document-bearing requests.
6. Add safe telemetry containing classification/result/reason metadata but never raw document content.
7. Add tests proving:
   - recognized metadata labels affect routing;
   - `SECRET -> RESTRICTED` can prevent an otherwise configured cloud route;
   - missing metadata does not become `PUBLIC`;
   - unknown labels do not silently downgrade;
   - parser failure cannot silently reduce classification;
   - classification is resolved before the provider receives document content.

---

## 6. Acceptance Criteria

- A supported document with a recognized classification metadata label can automatically produce the corresponding TramAI classification.
- Organization labels can be deterministically mapped to TramAI's canonical classification taxonomy.
- The classification participates in the same policy-aware provider/model eligibility path as an explicitly supplied `ClassifiedDocument`.
- Document content is not sent to an AI provider in order to read metadata.
- Missing, unknown, unsupported, or unreadable metadata never silently implies `PUBLIC`.
- Explicit classification metadata cannot be silently weakened by this feature.

---

## 7. Explicit Non-Goals for This Slice

0.7.0 metadata classification does **not** require:

- scanning arbitrary document text for words such as `CONFIDENTIAL` or `SECRET`;
- OCR-based label detection;
- header/footer/watermark recognition;
- DLP content classification;
- regex/content heuristics;
- LLM-based sensitivity inference;
- local-model classification;
- automatic relabeling of documents;
- integration with every enterprise labeling vendor or document-management system.

Those may be evaluated later against the same classification pipeline. They are not required to obtain the immediate value of honoring classification information the document already carries.

---

## 8. Product Principle

> **If the document already declares its classification, TramAI should read it before deciding where the document is allowed to go.**
