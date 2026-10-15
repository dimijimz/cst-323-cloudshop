import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Writes the five UML class diagram sources (uml-models.puml, uml-controllers.puml,
 * uml-services.puml, uml-repositories.puml, uml-logging.puml) from the compiled
 * application classes.
 *
 * <p>The diagrams are generated rather than drawn by hand so they cannot drift from
 * the code: every field, constructor and method in them, with its visibility,
 * parameter names, parameter types and return type, is read by reflection from
 * target/classes. Rename a method and the next render shows the new name. The
 * dashed "uses" arrows of the logging diagram are read from the class files too.
 *
 * <p>Not part of the application. render.sh runs it as a single-file program:
 * <pre>java docs/diagrams/tools/UmlClassDiagrams.java [project-root]</pre>
 * It expects the project to have been compiled, and target/plantuml/classpath.txt
 * to list the dependency jars (render.sh produces both).
 */
public class UmlClassDiagrams {

    private static final String BASE_PACKAGE = "edu.gcu.cst323.cloudshop";

    /** Signatures longer than this are wrapped, so a class box stays narrow enough to print. */
    private static final int WRAP_AT = 74;

    /** Starts a continuation line of a wrapped signature: a PlantUML line break, then an indent. */
    private static final String CONTINUATION = "\\n" + " ".repeat(8);

    /** An application type as a class file writes it inside a descriptor or a generic signature. */
    private static final Pattern DESCRIBED_TYPE = Pattern.compile("L(" + BASE_PACKAGE.replace('.', '/') + "/[\\w/$]+);");

    /**
     * One diagram: the packages it documents, where it goes, whether its classes are
     * stacked down the page with their collaborators beside them, whether it draws
     * which class uses which as well as which holds which, and its legend notes.
     */
    private record Diagram(List<String> packages, String file, String title, boolean leftToRight,
                           boolean dependencies, List<String> notes) {
    }

    private static final List<Diagram> DIAGRAMS = List.of(
            new Diagram(List.of("model"), "uml-models", "Model Classes", false, false,
                    List.of("JPA entities mapped to the users, products and purchases tables.",
                            "Purchase has no setters: a sale is a record and is never edited.")),
            new Diagram(List.of("controller"), "uml-controllers", "Controller Classes", true, false,
                    List.of("A public method returning String returns a view name or a redirect.",
                            "Routes: see the Pages and routes table in README.md.")),
            new Diagram(List.of("service"), "uml-services", "Service Classes", true, false,
                    List.of("PurchaseService.purchase runs the stock decrement and the",
                            "purchase insert in one transaction.")),
            new Diagram(List.of("repository"), "uml-repositories", "Repository Interfaces", true, false,
                    List.of("Spring Data JPA implements these interfaces at runtime.",
                            "Inherited from JpaRepository and not repeated here:",
                            "save, findById, findAll, count, delete, deleteAll, ...")),
            // The classes here hold no references to each other: they call static
            // methods and test exception types, so only the dashed arrows connect them.
            new Diagram(List.of("logging", "exception"), "uml-logging", "Logging and Error Handling", false, true,
                    List.of("LoggingAspect wraps every public method of the controller, service and",
                            "repository packages and writes the ENTER, EXIT and EXCEPTION lines.",
                            "GlobalExceptionHandler writes the HANDLED line with the same error code.",
                            "ERR-STOCK, ERR-NOTFOUND and ERR-VALIDATION are logged at WARN on one line;",
                            "ERR-UNEXPECTED is logged at ERROR, with its stack trace once.")));

    private static Path root;
    private static ClassLoader loader;

    public static void main(String[] args) throws Exception {
        root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        loader = applicationClassLoader();
        for (Diagram diagram : DIAGRAMS) {
            Path out = root.resolve("docs/diagrams").resolve(diagram.file() + ".puml");
            Files.writeString(out, render(diagram), StandardCharsets.UTF_8);
            System.out.println("wrote " + root.relativize(out));
        }
    }

    // --- loading ------------------------------------------------------------

    private static ClassLoader applicationClassLoader() throws IOException {
        Path classes = root.resolve("target/classes");
        Path classpathFile = root.resolve("target/plantuml/classpath.txt");
        if (!Files.isDirectory(classes) || !Files.isRegularFile(classpathFile)) {
            throw new IllegalStateException("Compile first and build the classpath file; run docs/diagrams/render.sh");
        }
        List<URL> urls = new ArrayList<>();
        urls.add(classes.toUri().toURL());
        for (String jar : Files.readString(classpathFile).trim().split(Pattern.quote(File.pathSeparator))) {
            urls.add(Path.of(jar.trim()).toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static List<Class<?>> classesIn(String pkg) throws Exception {
        Path dir = root.resolve("target/classes").resolve((BASE_PACKAGE + "." + pkg).replace('.', '/'));
        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                // Skips anonymous and nested classes, which carry a $ in their file name,
                // and package-info, which javac writes when a package has annotations.
                if (name.endsWith(".class") && !name.contains("$") && !name.equals("package-info.class")) {
                    String className = BASE_PACKAGE + "." + pkg + "." + name.substring(0, name.length() - 6);
                    found.add(Class.forName(className, false, loader));
                }
            }
        }
        return found;
    }

    // --- rendering ----------------------------------------------------------

    private static String render(Diagram diagram) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        for (String pkg : diagram.packages()) {
            classes.addAll(classesIn(pkg));
        }
        Set<Class<?>> inDiagram = new LinkedHashSet<>(classes);

        StringBuilder body = new StringBuilder();
        List<String> relations = new ArrayList<>();
        Map<String, String> collaborators = new LinkedHashMap<>();   // alias -> declaration

        for (Class<?> type : classes) {
            body.append(declaration(type)).append('\n');
            relations.addAll(relationsOf(type, inDiagram, collaborators, diagram.dependencies()));
        }

        StringBuilder out = new StringBuilder();
        out.append("@startuml ").append(diagram.file()).append('\n');
        out.append("' GENERATED by docs/diagrams/tools/UmlClassDiagrams.java from target/classes.\n");
        out.append("' Do not edit by hand: change the Java code and run docs/diagrams/render.sh.\n");
        out.append("!pragma layout smetana\n");
        if (diagram.leftToRight()) {
            out.append("left to right direction\n");
        }
        out.append("skinparam dpi 150\n");
        out.append("skinparam classAttributeIconSize 0\n");
        out.append("skinparam backgroundColor white\n");
        out.append("skinparam shadowing false\n");
        out.append("skinparam defaultFontName Arial\n");
        out.append("skinparam roundCorner 4\n");
        out.append("skinparam class {\n");
        out.append("    BackgroundColor #FBFCFE\n");
        out.append("    BorderColor #33506E\n");
        out.append("    ArrowColor #33506E\n");
        out.append("    HeaderBackgroundColor #DCE8F5\n");
        out.append("    FontStyle bold\n");
        out.append("    BackgroundColor<<external>> #F2F2F2\n");
        out.append("    HeaderBackgroundColor<<external>> #E4E4E4\n");
        out.append("    BorderColor<<external>> #8A8A8A\n");
        out.append("}\n");
        out.append("hide circle\n");
        out.append("hide <<external>> stereotype\n\n");
        out.append("title CloudShop - ").append(diagram.title())
                .append("\\n<size:11>").append(diagram.packages().size() == 1 ? "package " : "packages ")
                .append(diagram.packages().stream().map(pkg -> BASE_PACKAGE + "." + pkg).collect(Collectors.joining(" and ")))
                .append("</size>\n\n");

        out.append(body);
        collaborators.values().forEach(line -> out.append(line).append('\n'));
        if (!collaborators.isEmpty()) {
            out.append('\n');
        }
        relations.forEach(line -> out.append(line).append('\n'));

        out.append("\nlegend bottom left\n");
        out.append("    <b>+</b> public    <b>-</b> private    <b>#</b> protected    <u>underlined</u> static\n");
        if (!collaborators.isEmpty()) {
            out.append("    Grey boxes belong to another package or library and are detailed elsewhere.\n");
        }
        if (diagram.dependencies()) {
            out.append("    A dashed arrow points to a class that this one calls or tests for.\n");
        }
        diagram.notes().forEach(note -> out.append("    ").append(note).append('\n'));
        out.append("endlegend\n@enduml\n");
        return out.toString();
    }

    /** One class, interface or enum with its three compartments: name, attributes, operations. */
    private static String declaration(Class<?> type) throws IOException, IllegalAccessException {
        String source = sourceOf(type);
        StringBuilder out = new StringBuilder();

        String keyword = type.isEnum() ? "enum" : type.isInterface() ? "interface" : "class";
        out.append(keyword).append(' ').append(type.getSimpleName());
        String stereotype = stereotypeOf(type);
        if (!stereotype.isEmpty()) {
            out.append(" <<").append(stereotype).append(">>");
        }
        out.append(" {\n");

        // Attributes.
        List<Field> fields = Arrays.stream(type.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !f.isEnumConstant())
                .filter(f -> !f.getType().getSimpleName().equals("Logger"))
                .sorted(Comparator.comparingInt(f -> position(source, f.getName(), true)))
                .toList();
        if (type.isEnum()) {
            for (Object constant : type.getEnumConstants()) {
                out.append("    ").append(((Enum<?>) constant).name()).append(stateOf(constant, fields)).append('\n');
            }
        }
        for (Field field : fields) {
            out.append("    ").append(visibility(field.getModifiers())).append(' ')
                    .append(Modifier.isStatic(field.getModifiers()) ? "{static} " : "")
                    .append(field.getName()).append(": ").append(name(field.getGenericType())).append('\n');
        }

        // Operations: constructors and methods together, in source order.
        out.append("    --\n");
        List<Executable> operations = new ArrayList<>();
        if (!type.isEnum() && !type.isInterface()
                && position(source, type.getSimpleName(), false) != Integer.MAX_VALUE) {
            // Only when a constructor is written in the source. The default constructor the
            // compiler adds to a class that declares none is not something a reader will find.
            operations.addAll(Arrays.asList(type.getDeclaredConstructors()));
        }
        Arrays.stream(type.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .filter(m -> !(type.isEnum() && (m.getName().equals("values") || m.getName().equals("valueOf"))))
                .forEach(operations::add);
        operations.sort(Comparator
                .comparingInt((Executable e) -> position(source, operationName(e), false))
                .thenComparingInt(Executable::getParameterCount));
        for (Executable operation : operations) {
            out.append("    ").append(signature(operation)).append('\n');
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * What an enum constant holds in its fields, written the way its declaration
     * reads in source: STOCK("ERR-STOCK", true). Empty for an enum with no fields.
     */
    private static String stateOf(Object constant, List<Field> fields) throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(constant);
            values.add(value instanceof String ? "\"" + value + "\"" : String.valueOf(value));
        }
        return values.isEmpty() ? "" : "(" + String.join(", ", values) + ")";
    }

    private static String signature(Executable operation) {
        List<String> parameters = Arrays.stream(operation.getParameters())
                .map(p -> parameterName(p) + ": " + name(p.getParameterizedType()))
                .toList();
        String returns = operation instanceof Method method ? ": " + name(method.getGenericReturnType()) : "";

        StringBuilder out = new StringBuilder();
        out.append(visibility(operation.getModifiers())).append(' ');
        if (Modifier.isStatic(operation.getModifiers())) {
            out.append("{static} ");
        }

        // Fills each line up to WRAP_AT, then carries on from an indented continuation line.
        StringBuilder line = new StringBuilder(operationName(operation)).append('(');
        if (parameters.isEmpty()) {
            line.append(')').append(returns);
        }
        for (int i = 0; i < parameters.size(); i++) {
            boolean last = i == parameters.size() - 1;
            String piece = parameters.get(i) + (last ? ")" + returns : ", ");
            boolean lineHasParameters = line.charAt(line.length() - 1) != '(';
            if (lineHasParameters && line.length() + piece.length() > WRAP_AT) {
                out.append(line.toString().stripTrailing()).append(CONTINUATION);
                line.setLength(0);
            }
            line.append(piece);
        }
        return out.append(line).toString();
    }

    private static String parameterName(Parameter parameter) {
        if (!parameter.isNamePresent()) {
            throw new IllegalStateException("Parameter names are missing; compile with -parameters (the Spring Boot parent POM does)");
        }
        return parameter.getName();
    }

    private static String operationName(Executable operation) {
        return operation instanceof Constructor<?> ? operation.getDeclaringClass().getSimpleName() : operation.getName();
    }

    private static String visibility(int modifiers) {
        if (Modifier.isPublic(modifiers)) {
            return "+";
        }
        if (Modifier.isPrivate(modifiers)) {
            return "-";
        }
        return Modifier.isProtected(modifiers) ? "#" : "~";
    }

    /**
     * The role of a class, shown as its UML stereotype: its Spring, JPA or AspectJ
     * annotation, or failing that what kind of class it is.
     */
    private static String stereotypeOf(Class<?> type) {
        List<String> known = List.of("Entity", "Service", "RestController", "Controller", "Repository",
                "ControllerAdvice", "Configuration", "Aspect", "Component");
        Set<String> present = Arrays.stream(type.getAnnotations())
                .map(a -> a.annotationType().getSimpleName()).collect(Collectors.toSet());
        String role = known.stream().filter(present::contains).findFirst().orElse("");
        if (type.isEnum()) {
            return "enumeration";
        }
        if (type.isInterface()) {
            return role.isEmpty() ? "interface" : "interface, " + role;
        }
        if (!role.isEmpty()) {
            return role;
        }
        if (Throwable.class.isAssignableFrom(type)) {
            return "exception";
        }
        return isUtility(type) ? "utility" : "";
    }

    /** A class that is never instantiated: final, its constructors private, its methods all static. */
    private static boolean isUtility(Class<?> type) {
        return Modifier.isFinal(type.getModifiers())
                && Arrays.stream(type.getDeclaredConstructors()).allMatch(c -> Modifier.isPrivate(c.getModifiers()))
                && Arrays.stream(type.getDeclaredMethods()).allMatch(m -> Modifier.isStatic(m.getModifiers()));
    }

    // --- relationships ------------------------------------------------------

    private static List<String> relationsOf(Class<?> type, Set<Class<?>> inDiagram, Map<String, String> collaborators,
                                            boolean dependencies) throws IOException, ClassNotFoundException {
        List<String> lines = new ArrayList<>();
        String self = type.getSimpleName();
        Set<Class<?>> joined = new LinkedHashSet<>();   // already connected to this class by a line

        // Generalization and realization, including the generic arguments of JpaRepository<T, ID>.
        Class<?> superclass = type.getSuperclass();
        if (superclass != null && superclass != Object.class && superclass != Enum.class && superclass != Record.class) {
            String label = name(type.getGenericSuperclass());
            String alias = label.replaceAll("[^A-Za-z0-9]", "_");
            if (!inDiagram.contains(superclass)) {
                collaborators.putIfAbsent(alias, "class \"" + label + "\" as " + alias + " <<external>>\nhide " + alias + " members");
            }
            lines.add(alias + " <|-- " + self);
            joined.add(superclass);
        }
        for (Type parent : type.getGenericInterfaces()) {
            String label = name(parent);
            String alias = label.replaceAll("[^A-Za-z0-9]", "_");
            collaborators.putIfAbsent(alias, "interface \"" + label + "\" as " + alias + " <<external>>\nhide " + alias + " members");
            lines.add(alias + (type.isInterface() ? " <|-- " : " <|.. ") + self);
        }
        joined.addAll(Arrays.asList(type.getInterfaces()));

        // Associations: one arrow per field whose type is an application class.
        for (Field field : type.getDeclaredFields()) {
            if (field.isSynthetic() || field.isEnumConstant() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> target = field.getType();
            if (!target.getName().startsWith(BASE_PACKAGE) || target == type) {
                continue;
            }
            String targetName = target.getSimpleName();
            if (!inDiagram.contains(target)) {
                collaborators.putIfAbsent(targetName, "class " + targetName + " <<external>>\nhide " + targetName + " members");
            }
            boolean manyToOne = Arrays.stream(field.getAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("ManyToOne"));
            lines.add(manyToOne
                    ? self + " \"0..*\" --> \"1\" " + targetName + " : " + field.getName()
                    : self + " --> " + targetName + " : " + field.getName());
            joined.add(target);
        }

        // Dependencies: one dashed arrow per application class this one calls or tests
        // for without holding it in a field or inheriting from it.
        if (dependencies) {
            for (Class<?> used : usedBy(type)) {
                if (used == type || joined.contains(used)) {
                    continue;
                }
                String usedName = used.getSimpleName();
                if (!inDiagram.contains(used)) {
                    collaborators.putIfAbsent(usedName, "class " + usedName + " <<external>>\nhide " + usedName + " members");
                }
                lines.add(self + " ..> " + usedName);
            }
        }
        return lines;
    }

    /**
     * The application classes a class refers to anywhere in its code: in a call, a
     * type test, an annotation, a signature or a field. They are read from the
     * constant pools of its class file and of the classes nested in it, which
     * between them name every class the compiled code mentions, so no dependency
     * has to be declared here by hand.
     */
    private static List<Class<?>> usedBy(Class<?> type) throws IOException, ClassNotFoundException {
        Path directory = root.resolve("target/classes").resolve(type.getPackageName().replace('.', '/'));
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.equals(type.getSimpleName() + ".class") || fileName.startsWith(type.getSimpleName() + "$")) {
                    names.addAll(classNamesIn(file));
                }
            }
        }
        List<Class<?>> used = new ArrayList<>();
        for (String name : names) {
            if (!name.contains("$")) {
                used.add(Class.forName(name, false, loader));
            }
        }
        used.sort(Comparator.comparing(Class::getSimpleName));
        return used;
    }

    /**
     * Every application class one class file names, whether as a class reference
     * (a call, a cast, an instanceof, a catch) or inside a descriptor or signature.
     */
    private static Set<String> classNamesIn(Path classFile) throws IOException {
        Set<String> names = new TreeSet<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
            in.skipBytes(8);   // the magic number and the two version numbers
            int entries = in.readUnsignedShort();
            String[] text = new String[entries];
            List<Integer> classNames = new ArrayList<>();   // where in the pool each referenced class is named
            for (int i = 1; i < entries; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> text[i] = in.readUTF();
                    case 7 -> classNames.add(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> {
                        in.skipBytes(8);
                        i++;   // a long or a double takes up two entries
                    }
                    default -> throw new IllegalStateException("Unknown constant pool tag " + tag + " in " + classFile);
                }
            }
            for (int index : classNames) {
                String name = text[index].replace('/', '.');
                if (name.startsWith(BASE_PACKAGE + ".")) {
                    names.add(name);
                }
            }
            for (String entry : text) {
                Matcher described = DESCRIBED_TYPE.matcher(entry == null ? "" : entry);
                while (described.find()) {
                    names.add(described.group(1).replace('/', '.'));
                }
            }
        }
        return names;
    }

    // --- type names ---------------------------------------------------------

    /** A type as it reads in source, with simple names and its generic arguments kept. */
    private static String name(Type type) {
        if (type instanceof Class<?> c) {
            return c.isArray() ? name(c.getComponentType()) + "[]" : c.getSimpleName();
        }
        if (type instanceof ParameterizedType p) {
            return name(p.getRawType()) + "<"
                    + Arrays.stream(p.getActualTypeArguments()).map(UmlClassDiagrams::name).collect(Collectors.joining(", "))
                    + ">";
        }
        if (type instanceof GenericArrayType a) {
            return name(a.getGenericComponentType()) + "[]";
        }
        if (type instanceof TypeVariable<?> v) {
            return v.getName();
        }
        if (type instanceof WildcardType w) {
            if (w.getLowerBounds().length > 0) {
                return "? super " + name(w.getLowerBounds()[0]);
            }
            Type upper = w.getUpperBounds()[0];
            return upper == Object.class ? "?" : "? extends " + name(upper);
        }
        return type.getTypeName();
    }

    // --- source order -------------------------------------------------------

    private static String sourceOf(Class<?> type) throws IOException {
        Path file = root.resolve("src/main/java").resolve(type.getName().replace('.', '/') + ".java");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Where a member is declared in its source file, so the diagram lists members in
     * the order a reader of the code meets them. Reflection alone does not promise
     * any order. A declaration is recognised as a class-level line (indented exactly
     * four spaces, so statements inside method bodies do not count) on which the name
     * is not part of a call or an assignment.
     *
     * @return the offset of the declaration, or Integer.MAX_VALUE if the source has none
     */
    private static int position(String source, String memberName, boolean field) {
        String suffix = field ? "\\s*[;=]" : "\\(";
        Matcher declared = Pattern.compile("(?m)^ {4}(?! )[^\\n=.(]*\\b" + Pattern.quote(memberName) + suffix)
                .matcher(source);
        return declared.find() ? declared.start() : Integer.MAX_VALUE;
    }
}
