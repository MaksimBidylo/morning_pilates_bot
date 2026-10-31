package morningpilates.architecture

import com.tngtech.archunit.base.DescribedPredicate.not
import com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test

@Tag("architecture")
class ArchitectureTest {
    @ParameterizedTest(name = "{0} must not depend on {1}")
    @CsvSource(
        "training, telegram|infrastructure",
        "profile, telegram|infrastructure",
        "infrastructure, telegram",
        "flow, telegram|infrastructure|training|profile",
        "user, training|profile|flow|telegram|infrastructure",
    )
    fun `component boundaries are respected`(
        component: String,
        forbidden: String,
    ) {
        noClasses()
            .that()
            .resideInAPackage("morningpilates.$component..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(*forbidden.split('|').map { "morningpilates.$it.." }.toTypedArray())
            .check(productionClasses)
    }

    @Test
    fun `only bootstrap and application entry point depend on bootstrap`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("morningpilates.bootstrap..")
            .and()
            .haveNameNotMatching("morningpilates\\.ApplicationKt(\\$.*)?")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("morningpilates.bootstrap..")
            .check(productionClasses)
    }

    @Test
    fun `telegram does not depend on infrastructure classes`() {
        noClasses()
            .that()
            .resideInAPackage("morningpilates.telegram..")
            .should()
            .dependOnClassesThat(resideInAPackage("morningpilates.infrastructure..").and(not(INTERFACES)))
            .check(productionClasses)
    }

    @ParameterizedTest
    @ValueSource(strings = ["morningpilates.training.TrainingRepository", "morningpilates.profile.ProfileRepository"])
    fun `concrete repositories reside in infrastructure`(contract: String) {
        classes()
            .that()
            .areAssignableTo(contract)
            .and()
            .areNotInterfaces()
            .and()
            .doNotHaveModifier(ABSTRACT)
            .should()
            .resideInAPackage("morningpilates.infrastructure..")
            .check(productionClasses)
    }

    @ParameterizedTest(name = "no cycles in {0}")
    @ValueSource(
        strings = [
            "morningpilates.(*)..", "(**)", "morningpilates.config.(*)..",
            "morningpilates.telegram.(*)..", "morningpilates.telegram.training.(*)..",
            "morningpilates.flow.(*)..", "morningpilates.infrastructure.(*)..",
        ],
    )
    fun `component and nested package dependencies have no cycles`(pattern: String) {
        slices()
            .matching(pattern)
            .should()
            .beFreeOfCycles()
            .check(productionClasses)
    }

    companion object {
        private val productionClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("morningpilates")
    }
}
