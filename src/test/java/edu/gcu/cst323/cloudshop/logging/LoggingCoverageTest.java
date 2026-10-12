package edu.gcu.cst323.cloudshop.logging;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves how far {@link LoggingAspect} reaches: every public method of every
 * controller, service and repository is traced, with none left out.
 *
 * <p>{@link LoggingAspectTest} proves what the lines look like, on a sample of
 * calls. This test is about reach. The classes are found by scanning the three
 * packages rather than listed here, so a class added to one of them later is
 * checked the day it is added, and so is every method it declares or inherits.
 */
@SpringBootTest
@ActiveProfiles({"h2", "test"})
class LoggingCoverageTest {

    private static final String BASE_PACKAGE = "edu.gcu.cst323.cloudshop";

    private static final List<String> LAYERS = List.of("controller", "service", "repository");

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the scan finds the controllers, the services and the repositories, so the checks below are not passing on nothing")
    void theScanFindsAllThreeLayers() throws Exception {
        assertThat(typesIn("controller")).extracting(Class::getSimpleName).contains(
                "AuthController", "CatalogController", "HealthController", "HomeController",
                "OwnerProductController", "PurchaseController", "SalesController");
        assertThat(typesIn("service")).extracting(Class::getSimpleName)
                .contains("ProductService", "PurchaseService", "UserService");
        assertThat(typesIn("repository")).extracting(Class::getSimpleName)
                .contains("ProductRepository", "PurchaseRepository", "UserRepository");
    }

    @Test
    @DisplayName("every class in the three packages is a Spring bean, and the bean is a proxy that carries the logging advice")
    void everyClassIsAdvised() throws Exception {
        List<Class<?>> types = typesInEveryLayer();

        SoftAssertions.assertSoftly(softly -> {
            for (Class<?> type : types) {
                Object bean = context.getBeanProvider(type).getIfUnique();
                // Only a call that goes through a Spring proxy can be intercepted.
                softly.assertThat(bean).as("%s is a Spring bean", type.getName()).isNotNull();
                softly.assertThat(loggingAdvisorOf(bean)).as("%s carries the logging advice", type.getName()).isPresent();
            }
        });
    }

    @Test
    @DisplayName("the advice matches every public method of each of them, inherited ones included, and none is static or final, which a proxy cannot intercept")
    void everyPublicMethodIsAdvised() throws Exception {
        List<Class<?>> types = typesInEveryLayer();

        SoftAssertions.assertSoftly(softly -> {
            for (Class<?> type : types) {
                Object bean = context.getBeanProvider(type).getIfUnique();
                Optional<PointcutAdvisor> logging = loggingAdvisorOf(bean);
                if (logging.isEmpty()) {
                    softly.fail("%s has no logging advice, so none of its methods is traced", type.getName());
                    continue;
                }
                Pointcut pointcut = logging.get().getPointcut();
                Class<?> targetClass = AopUtils.getTargetClass(bean);

                List<Method> publicMethods = publicMethodsOf(type);
                softly.assertThat(publicMethods).as("public methods of %s", type.getName()).isNotEmpty();
                for (Method method : publicMethods) {
                    softly.assertThat(pointcut.getMethodMatcher().matches(method, targetClass))
                            .as("the logging advice matches %s", method)
                            .isTrue();
                    softly.assertThat(Modifier.isStatic(method.getModifiers()) || Modifier.isFinal(method.getModifiers()))
                            .as("%s is static or final, so a call to it would bypass the proxy", method)
                            .isFalse();
                }
            }
        });
    }

    // --- helpers ------------------------------------------------------------

    private static List<Class<?>> typesInEveryLayer() throws Exception {
        List<Class<?>> types = new ArrayList<>();
        for (String layer : LAYERS) {
            types.addAll(typesIn(layer));
        }
        return types;
    }

    /** Every top-level class and interface compiled into one layer's package, sub-packages included. */
    private static List<Class<?>> typesIn(String layer) throws Exception {
        String pattern = "classpath*:" + (BASE_PACKAGE + "." + layer).replace('.', '/') + "/**/*.class";
        CachingMetadataReaderFactory metadata = new CachingMetadataReaderFactory();

        List<Class<?>> types = new ArrayList<>();
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            ClassMetadata type = metadata.getMetadataReader(resource).getClassMetadata();
            // A nested or anonymous class is part of the class that holds it, and
            // package-info holds no code.
            if (!type.hasEnclosingClass() && !type.getClassName().endsWith("package-info")) {
                types.add(Class.forName(type.getClassName()));
            }
        }
        types.sort(Comparator.comparing(Class::getName));
        return types;
    }

    /**
     * The public methods a caller can reach on a type, whichever class or
     * interface declares them, leaving out the ones every object has. For a
     * repository that includes everything inherited from Spring Data.
     */
    private static List<Method> publicMethodsOf(Class<?> type) {
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .sorted(Comparator.comparing(Method::toString))
                .toList();
    }

    private static Optional<PointcutAdvisor> loggingAdvisorOf(Object bean) {
        if (!(bean instanceof Advised advised)) {
            return Optional.empty();
        }
        return Arrays.stream(advised.getAdvisors())
                .filter(PointcutAdvisor.class::isInstance)
                .map(PointcutAdvisor.class::cast)
                .filter(advisor -> advisor.getAdvice() instanceof AbstractAspectJAdvice advice
                        && "loggingAspect".equals(advice.getAspectName()))
                .findFirst();
    }
}
