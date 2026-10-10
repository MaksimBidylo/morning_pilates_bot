package morningpilates.training

import morningpilates.flow.FlowResult
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertContains
import kotlin.test.assertIs

abstract class TrainingFlowServiceTestBase {
    protected val trainingRepository = FakeTrainingRepository()
    protected val service = TrainingFlowService(trainingRepository)

    protected fun populatedTrainingDetails(): TrainingDetails = TrainingDetails(
        date = LocalDate.of(2026, 10, 9),
        time = LocalTime.of(19, 30),
        techniques = mutableListOf(
            TechniqueDraft(name = "Armbar", description = "Рычаг локтя из маунта"),
            TechniqueDraft(name = "Triangle", description = "Треугольник из гарда"),
        ),
        note = "Заметка к тренировке",
    )

    protected fun trainingDetails(note: String? = null): TrainingDetails = TrainingDetails(
        date = LocalDate.of(2026, 10, 9),
        time = LocalTime.of(19, 30),
        techniques = mutableListOf(
            TechniqueDraft(name = "Armbar", description = "Рычаг локтя из маунта"),
            TechniqueDraft(name = "Triangle", description = null),
        ),
        note = note,
    )

    protected fun TrainingDetails.snapshot(): TrainingDetails =
        copy(techniques = techniques.toList())

    protected fun assertRejected(result: FlowResult, expectedReason: String) {
        val rejected = assertIs<FlowResult.Rejected>(result)
        assertContains(rejected.reason, expectedReason)
    }
}
