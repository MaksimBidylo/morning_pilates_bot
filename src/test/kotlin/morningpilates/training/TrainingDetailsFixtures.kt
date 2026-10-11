package morningpilates.training

import java.time.LocalDate
import java.time.LocalTime

private val DEFAULT_TRAINING_DATE = LocalDate.of(2026, 10, 9)
private val DEFAULT_START_TIME = LocalTime.of(19, 30)
private const val ARM_BAR_NAME = "Armbar"
private const val ARM_BAR_DESCRIPTION = "Рычаг локтя из маунта"
private const val TRIANGLE_NAME = "Triangle"
private const val TRIANGLE_DESCRIPTION = "Треугольник из гарда"
private const val DEFAULT_TRAINING_NOTE = "Заметка к тренировке"

internal fun minimalTrainingDetails(): TrainingDetails =
    TrainingDetails(
        date = DEFAULT_TRAINING_DATE,
        time = DEFAULT_START_TIME,
        techniques = mutableListOf(),
        note = null,
    )

internal fun populatedTrainingDetails(): TrainingDetails =
    TrainingDetails(
        date = DEFAULT_TRAINING_DATE,
        time = DEFAULT_START_TIME,
        techniques =
            mutableListOf(
                TechniqueDraft(name = ARM_BAR_NAME, description = ARM_BAR_DESCRIPTION),
                TechniqueDraft(name = TRIANGLE_NAME, description = TRIANGLE_DESCRIPTION),
            ),
        note = DEFAULT_TRAINING_NOTE,
    )

internal fun trainingDetails(note: String? = null): TrainingDetails =
    TrainingDetails(
        date = DEFAULT_TRAINING_DATE,
        time = DEFAULT_START_TIME,
        techniques =
            mutableListOf(
                TechniqueDraft(name = ARM_BAR_NAME, description = ARM_BAR_DESCRIPTION),
                TechniqueDraft(name = TRIANGLE_NAME, description = null),
            ),
        note = note,
    )

internal fun TrainingDetails.snapshot(): TrainingDetails = copy(techniques = techniques.toList())
