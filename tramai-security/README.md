# tramai-security

## Rule-Based Document Classification

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.classification.ClassificationInput
import dev.tramai.security.classification.ClassificationRule
import dev.tramai.security.classification.RuleBasedClassifierConfiguration
import dev.tramai.security.classification.RuleBasedDocumentClassifier
import dev.tramai.security.classification.classifyDocument

data class InvoiceDocument(val invoiceId: String, val body: String)
data class InvoiceAssessment(val risk: String)

@AiService
interface InvoiceAnalyzer {
    @Operation("Assess invoice handling risk")
    suspend fun assess(invoice: ClassifiedDocument<InvoiceDocument>): InvoiceAssessment
}

val classifier = RuleBasedDocumentClassifier(
    RuleBasedClassifierConfiguration(
        defaultClassification = DataClassification.INTERNAL,
        rules = listOf(
            ClassificationRule(
                id = "bank-account",
                classification = DataClassification.CONFIDENTIAL,
                pattern = "\\bIBAN\\b",
            ),
            ClassificationRule(
                id = "customer-ssn",
                classification = DataClassification.RESTRICTED,
                pattern = "\\b\\d{3}-\\d{2}-\\d{4}\\b",
            ),
        ),
    ),
)

val invoice = InvoiceDocument("inv-123", "Customer SSN 123-45-6789")
val classifiedInvoice = classifier.classifyDocument(
    payload = invoice,
    input = ClassificationInput(
        text = invoice.body,
        metadata = mapOf("documentType" to "invoice"),
    ),
)
```
