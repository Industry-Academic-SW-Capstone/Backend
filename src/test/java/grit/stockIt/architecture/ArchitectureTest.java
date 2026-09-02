package grit.stockIt.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 패키지 구조·네이밍 규칙 검증 (docs/conventions.md 참고).
 *
 * <p>FreezingArchRule로 기존 위반은 {@code src/test/resources/archunit_store}에
 * 기록되어 있으며, 새 위반만 테스트 실패로 나타난다. 기존 위반을 해소하면
 * store에서 자동으로 제거된다.
 */
@AnalyzeClasses(packages = "grit.stockIt", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule DTO_NAMING = FreezingArchRule.freeze(
            classes().that().resideInAPackage("..dto..")
                    .and().areTopLevelClasses()
                    .and().areNotEnums()
                    .and().areNotInterfaces()
                    .should().haveSimpleNameEndingWith("Request")
                    .orShould().haveSimpleNameEndingWith("Response")
                    .orShould().haveSimpleNameEndingWith("Event")
                    .because("dto 패키지의 최상위 클래스(Enum, 인터페이스 제외)는 Request/Response/Event로 끝나야 한다"));

    @ArchTest
    static final ArchRule CONTROLLERS_MUST_NOT_USE_REPOSITORIES = FreezingArchRule.freeze(
            noClasses().that().resideInAPackage("grit.stockIt..controller..")
                    .should().dependOnClassesThat().resideInAPackage("grit.stockIt..repository..")
                    .because("컨트롤러는 리포지토리를 직접 참조하지 않고 서비스를 경유해야 한다"));

    @ArchTest
    static final ArchRule TRANSACTION_PARTICIPANTS_MUST_NOT_DECLARE_CLASS_TRANSACTION =
            noClasses().that().haveSimpleName("MemberRegistrationService")
                    .or().haveSimpleName("MemberTitleService")
                    .should().beAnnotatedWith(Transactional.class)
                    .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
                    .because("호출자 트랜잭션에 참여하는 협력자가 클래스 레벨 경계를 선언하면 호출자 롤백과 분리되어 부분 가입·부분 반영이 남는다");

    @ArchTest
    static final ArchRule PARTICIPATING_METHODS_MUST_NOT_DECLARE_TRANSACTION =
            noMethods().that().areDeclaredInClassesThat().haveSimpleName("MemberRegistrationService")
                    .or().haveName("equipRepresentativeTitle")
                    .should().beAnnotatedWith(Transactional.class)
                    .orShould().beAnnotatedWith(jakarta.transaction.Transactional.class)
                    .because("호출자 트랜잭션에 참여하는 메서드가 전파 속성을 선언하면 트랜잭션이 분열되는데, 어느 테스트도 이를 관측하지 못한다");
}
