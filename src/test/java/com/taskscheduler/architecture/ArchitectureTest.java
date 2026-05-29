package com.taskscheduler.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

/**
 * Architecture rules enforced as automated tests.
 *
 * These tests catch the slow erosion of intended structure under
 * deadline pressure — "architectural drift".
 *
 * Without these, a developer under pressure adds a Kafka import
 * directly into a domain service. It works. It ships. Six months
 * later, swapping Kafka for Pulsar means touching domain code.
 * ArchUnit makes that impossible to slip through code review.
 */
@AnalyzeClasses(
        packages = "com.taskscheduler",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ArchitectureTest {

    /**
     * Domain must never depend on infrastructure.
     * Dependency arrows point inward — toward the domain, never outward.
     */
    @ArchTest
    static final ArchRule domainShouldNotDependOnInfrastructure =
            noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..infrastructure..")
                    .because("Domain layer must not know about infrastructure adapters");

    /**
     * Controllers must never bypass services and call repositories directly.
     */
    @ArchTest
    static final ArchRule controllersShouldNotAccessRepositories =
            noClasses()
                    .that().areAnnotatedWith(RestController.class)
                    .should().dependOnClassesThat()
                    .resideInAPackage("..domain.repository..")
                    .because("Controllers must delegate to services, not call repositories directly");

    /**
     * All service classes must declare transaction boundaries explicitly.
     * Prevents accidental non-transactional database operations.
     */
    @ArchTest
    static final ArchRule servicesShouldBeTransactional =
            classes()
                    .that().areAnnotatedWith(Service.class)
                    .should().beAnnotatedWith(Transactional.class)
                    .because("All @Service classes must define transaction boundaries");

    /**
     * Domain exceptions must live in the domain.exception package.
     * Prevents exception classes scattered across layers.
     */
    @ArchTest
    static final ArchRule exceptionsShouldLiveInDomainExceptionPackage =
            classes()
                    .that().haveSimpleNameEndingWith("Exception")
                    .and().resideInAPackage("com.taskscheduler..")
                    .should().resideInAPackage("..domain.exception..")
                    .because("All domain exceptions belong in the domain.exception package");
}