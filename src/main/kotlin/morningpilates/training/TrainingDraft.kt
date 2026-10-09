package morningpilates.training

import java.time.LocalDate
import java.time.LocalTime

data class TrainingDraft(
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val techniques: List<TechniqueDraft> = emptyList(),
    val note: String? = null,
)

data class TechniqueDraft(
    val name: String,
    val description: String? = null,
)
