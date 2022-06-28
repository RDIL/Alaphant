package alaphant.build.helpers;

import net.fabricmc.mappingio.MappedElementKind;
import net.fabricmc.mappingio.MappingVisitor;
import org.gradle.api.logging.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MappingAnalysisHelper implements MappingVisitor {
    String currentField;
    String currentClass;
    String currentMethod;

    int obfClasses = 0;
    int deobfClasses = 0;
    int obfFields = 0;
    int deobfFields = 0;
    int obfMethods = 0;
    int deobfMethods = 0;
    // packages will occur multiple times.
    Set<String> obfPackages = new HashSet<>();
    Set<String> deobfPackages = new HashSet<>();

    @Override
    public void visitNamespaces(String srcNamespace, List<String> dstNamespaces) {

    }

    @Override
    public boolean visitClass(String srcName) {
        this.currentClass = srcName;
        return true;
    }

    @Override
    public boolean visitField(String srcName, String srcDesc) {
        this.currentField = srcName;
        return true;
    }

    @Override
    public boolean visitMethod(String srcName, String srcDesc) {
        this.currentMethod = srcName;
        return true;
    }

    @Override
    public boolean visitMethodArg(int argPosition, int lvIndex, String srcName) {
        return false;
    }

    @Override
    public boolean visitMethodVar(int lvtRowIndex, int lvIndex, int startOpIdx, String srcName) {
        return false;
    }

    @Override
    public void visitDstName(MappedElementKind targetKind, int namespace, String name) {
        switch (targetKind) {
            case CLASS:
                final ObfuscatedPath p = checkPathObfuscation(name);

                if (p.isClassObf) {
                    this.obfClasses++;
                } else {
                    this.deobfClasses++;
                }

                if (p.isPackageObf) {
                    this.obfPackages.add(p.packageName);
                } else {
                    this.deobfPackages.add(p.packageName);
                }
            case FIELD:
                if (isObfuscatedFieldName(name)) {
                    this.obfFields++;
                } else {
                    this.deobfFields++;
                }
            case METHOD:
                if (isObfuscatedMethodName(name)) {
                    this.obfMethods++;
                } else {
                    this.deobfMethods++;
                }
            default: {}
        }
    }

    @Override
    public void visitComment(MappedElementKind targetKind, String comment) {

    }

    private static boolean isObfuscatedType(@NotNull final String name, @NotNull final String typeName) {
        return name.contains("unk_") || name.contains(typeName + "_");
    }

    private static boolean isObfuscatedMethodName(@NotNull final String name) {
        return isObfuscatedType(name, "method");
    }

    private static boolean isObfuscatedFieldName(@NotNull final String name) {
        return isObfuscatedType(name, "field");
    }

    public void logResults(@NotNull final Logger logger) {
        final int totalClasses = this.obfClasses + this.deobfClasses;
        final int totalFields = this.obfFields + this.deobfFields;
        final int totalMethods = this.obfMethods + this.deobfMethods;
        final int totalPackages = this.obfPackages.size() + this.deobfPackages.size();

        final int percentClasses = this.deobfClasses * 100 / totalClasses;
        final int percentFields = this.deobfFields * 100 / totalFields;
        final int percentMethods = this.deobfMethods * 100 / totalMethods;
        final int percentPackages = this.deobfPackages.size() * 100 / totalPackages;
        final int averageTotal = (percentClasses + percentMethods + percentFields + percentPackages) / 4;

        logger.lifecycle("====== ANALYSIS RESULTS ======");
        logger.lifecycle("Classes: " + this.deobfClasses + "/" + totalClasses + " deobfuscated (" + percentClasses + "%!)");
        logger.lifecycle("Fields: " + this.deobfFields + "/" + totalFields + " deobfuscated (" + percentFields + "%!)");
        logger.lifecycle("Methods: " + this.deobfMethods + "/" + totalMethods + " deobfuscated (" + percentMethods + "%!)");
        logger.lifecycle("Packages: " + this.deobfPackages.size() + "/" + totalPackages + " deobfuscated (" + percentPackages + "%!)");
        logger.lifecycle("Average: " + averageTotal + "%");
    }

    private static class ObfuscatedPath {
        final boolean isClassObf;
        final boolean isPackageObf;
        final String packageName;

        private ObfuscatedPath(final boolean isClassObf, final boolean isPackageObf, final String packageName) {
            this.isClassObf = isClassObf;
            this.isPackageObf = isPackageObf;
            this.packageName = packageName;
        }
    }

    private static @NotNull ObfuscatedPath checkPathObfuscation(@NotNull final String name) {
        final String[] split = name.split("/");

        final String className = split[split.length - 1];
        final boolean classNameObf = className.length() <= 3 || className.startsWith("uclass");
        boolean packageNameObf = false;
        final StringBuilder packageNameBuilder = new StringBuilder();

        for (int i = 0; i < split.length; i++) {
            if (i == split.length - 1) {
                // we've made it to the class name
                break;
            }

            if (i != 0) {
                packageNameBuilder.append(".");
            }

            packageNameBuilder.append(split[i]);

            if (split[i].length() < 3) {
                packageNameObf = true;
                break;
            }
        }

        return new ObfuscatedPath(classNameObf, packageNameObf, packageNameBuilder.toString());
    }
}
