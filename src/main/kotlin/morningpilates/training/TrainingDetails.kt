package morningpilates.training

import java.time.LocalDate
import java.time.LocalTime

data class TrainingDetails(
    val date: LocalDate,
    val time: LocalTime,
    val techniques: List<TechniqueDraft> = emptyList(),
    val note: String? = null,
)

data class TechniqueDraft(
    val name: String,
    val description: String? = null,
)
