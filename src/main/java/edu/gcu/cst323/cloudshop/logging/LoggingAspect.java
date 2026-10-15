package edu.gcu.cst323.cloudshop.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Proxy;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * The interceptor that traces every call into the controller, service and
 * repository layers, so no class in those layers has to log its own entry and exit.
 *
 * <p>Each intercepted call writes, through the logger of the class that was called:
 * <pre>
 * ENTER ClassName.method(args)                      DEBUG
 * EXIT ClassName.method -&gt; result [N ms]            DEBUG
 * EXCEPTION ClassName.method [CODE] Type: message   WARN or ERROR
 * </pre>
 * A call ends in EXIT or in EXCEPTION, never both. {@link LogValues} decides what
 * may be said about an argument or a result, and {@link ExceptionLog} decides the
 * level of an exception line and whether it carries the stack trace.
 *
 * <p>The order puts this advice outside the transaction advice on a service. A
 * service's EXIT line and its time therefore include the commit, and a failure at
 * commit is reported against the method that owned the transaction.
 *
 * @author Dimitri Jimenez
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class LoggingAspect {

    /** The application's base package; an interface from it stands in for a repository's proxy class. */
    private static final String APPLICATION_PACKAGE = "edu.gcu.cst323.cloudshop";

    /** Any method declared by a class in the controller package. */
    @Pointcut("within(edu.gcu.cst323.cloudshop.controller..*)")
    public void controllerLayer() {
    }

    /** Any method declared by a class in the service package. */
    @Pointcut("within(edu.gcu.cst323.cloudshop.service..*)")
    public void serviceLayer() {
    }

    /**
     * Any method of a repository, including the ones it inherits from Spring Data.
     *
     * <p>The trailing {@code +} is what makes this match. A repository here is only
     * an interface; the bean is a proxy class that Spring Data generates in a JDK
     * package, so {@code within(...repository..*)} on its own matches nothing at
     * all. With the {@code +} the pointcut also takes any subtype of a type in the
     * package, and the proxy is one because it implements the interface. That
     * covers {@code save} and {@code findById}, which are declared by Spring Data
     * rather than in this package, as well as the query methods declared here.
     */
    @Pointcut("within(edu.gcu.cst323.cloudshop.repository..*+)")
    public void repositoryLayer() {
    }

    /** Public methods only, leaving out toString, equals and hashCode. */
    @Pointcut("execution(public * *(..)) && !execution(* java.lang.Object.*(..))")
    public void publicMethod() {
    }

    /**
     * Logs one call: its entry, then either its exit or its exception.
     *
     * @param joinPoint the intercepted call
     * @return whatever the intercepted method returned, unchanged
     * @throws Throwable whatever the intercepted method threw, unchanged
     */
    @Around("publicMethod() && (controllerLayer() || serviceLayer() || repositoryLayer())")
    public Object logInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        Class<?> type = interceptedType(joinPoint);
        Logger log = LoggerFactory.getLogger(type);
        String method = type.getSimpleName() + "." + joinPoint.getSignature().getName();

        if (log.isDebugEnabled()) {
            log.debug("ENTER {}({})", method, describeArguments(joinPoint));
        }
        long startedAt = System.nanoTime();

        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            ExceptionLog.write(log, "EXCEPTION " + method, ex);
            throw ex;
        }

        if (log.isDebugEnabled()) {
            log.debug("EXIT {} -> {} [{} ms]", method, describeResult(joinPoint, result),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        }
        return result;
    }

    /**
     * Finds the class whose logger and name the lines should carry.
     *
     * <p>For a controller or service that is the class itself. A repository bean
     * has no class of its own in this application, only a generated proxy, so the
     * repository interface it implements stands in for it.
     *
     * @param joinPoint the intercepted call
     * @return the class, or for a repository the interface, whose logger and simple
     *         name the lines use
     */
    private static Class<?> interceptedType(ProceedingJoinPoint joinPoint) {
        Object target = joinPoint.getTarget();
        if (target == null) {
            return joinPoint.getSignature().getDeclaringType();
        }
        Class<?> type = ClassUtils.getUserClass(target);
        if (!Proxy.isProxyClass(type)) {
            return type;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            if (implemented.getPackageName().startsWith(APPLICATION_PACKAGE)) {
                return implemented;
            }
        }
        return joinPoint.getSignature().getDeclaringType();
    }

    /**
     * Lists a call's arguments as {@code name=value} pairs, each value reduced by
     * {@link LogValues#argument} to what the log may say about it.
     *
     * @param joinPoint the intercepted call
     * @return the pairs separated by commas, or an empty string for a call without arguments
     */
    private static String describeArguments(ProceedingJoinPoint joinPoint) {
        Object[] values = joinPoint.getArgs();
        if (values.length == 0) {
            return "";
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // Present because Spring Boot's build compiles with -parameters; a library
        // compiled without it yields null, and the arguments are then numbered.
        String[] names = signature.getParameterNames();
        Class<?>[] types = signature.getParameterTypes();

        StringJoiner described = new StringJoiner(", ");
        for (int i = 0; i < values.length; i++) {
            String name = names != null && i < names.length ? names[i] : "arg" + i;
            Class<?> declaredType = i < types.length ? types[i] : null;
            described.add(name + "=" + LogValues.argument(declaredType, values[i]));
        }
        return described.toString();
    }

    /**
     * Describes what a call returned, by its type only.
     *
     * @param joinPoint the intercepted call, which supplies the declared return type
     * @param result    the value the call returned
     * @return the description {@link LogValues#result} gives
     */
    private static String describeResult(ProceedingJoinPoint joinPoint, Object result) {
        return LogValues.result(((MethodSignature) joinPoint.getSignature()).getReturnType(), result);
    }
}
