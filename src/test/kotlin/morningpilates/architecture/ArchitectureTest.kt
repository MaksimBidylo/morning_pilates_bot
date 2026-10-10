package morningpilates.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import kotlin.test.Test

class ArchitectureTest {
    @Test
    fun `feature packages do not depend on telegram`() {
        noClasses()
            .that()
            .resideInAnyPackage("morningpilates.training..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("morningpilates.telegram..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `feature packages do not depend on infrastructure`() {
        noClasses()
            .that()
            .resideInAnyPackage("morningpilates.training..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("morningpilates.infrastructure..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `main application packages have no dependency cycles`() {
        slices()
            .matching("morningpilates.(*)..")
            .should()
            .beFreeOfCycles()
            .check(classes)
    }

    companion object {
        private val classes =
            ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("morningpilates")
    }
}
