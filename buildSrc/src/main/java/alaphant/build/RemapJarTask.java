package alaphant.build;

import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

public class RemapJarTask extends DefaultTask {
    @InputFile
    private File inputJar;
    @OutputFile
    private File outputJar;
    @InputFile
    private File mappings;
    @Input
    private String remapGoal;

    public File getInputJar() {
        return inputJar;
    }

    public void setInputJar(@NotNull final File inputJar) {
        this.inputJar = inputJar;
        this.getInputs().file(inputJar);
    }

    public File getOutputJar() {
        return outputJar;
    }

    public void setOutputJar(@NotNull final File outputJar) {
        this.outputJar = outputJar;
        this.getOutputs().file(outputJar);
    }

    public File getMappings() {
        return mappings;
    }

    public void setMappings(@NotNull final File mappings) {
        this.mappings = mappings;
        this.getInputs().file(mappings);
    }

    public String getRemapGoal() {
        return remapGoal;
    }

    public void setRemapGoal(@NotNull final String remapGoal) {
        this.remapGoal = remapGoal;
    }

    @SuppressWarnings("Convert2Lambda")
    public RemapJarTask() {
        this.setGroup("mappings");
        this.setDescription("Create a version of the Charles Jar with human-readable names.");

        this.doLast(new Action<>() {
            @Override
            public void execute(@NotNull Task task) {
                RemapJarTask.this.getLogger().lifecycle("Remapping Charles from " + RemapJarTask.this.getRemapGoal());
                try {
                    RemapJarTask.this.mapJar();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    private String @NotNull [] getRemapFromAndTo() {
        final String remapGoal = this.getRemapGoal();

        final String[] parts = remapGoal.split("->");
        assert parts.length == 2;

        return parts;
    }

    private void mapJar() throws IOException {
        final File input = this.getInputJar();
        final File output = this.getOutputJar();
        final File mappings = this.getMappings();

        final String[] parts = this.getRemapFromAndTo();
        final String from = parts[0];
        final String to = parts[1];

        if (output.exists()) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
        }

        final TinyRemapper.Builder remapperBuilder = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(mappings.toPath(), from, to))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .invalidLvNamePattern(Pattern.compile("\\$\\$\\d+"))
                .inferNameFromSameLvIndex(true);

        final TinyRemapper remapper = remapperBuilder.build();

        remapper.readInputs(mappings.toPath());

        final OutputConsumerPath.Builder outputConsumerBuilder = new OutputConsumerPath.Builder(this.getOutputJar().toPath());
        // expose output consumer builder to function if there is need in the future
        final OutputConsumerPath outputConsumer = outputConsumerBuilder.build();

        outputConsumer.addNonClassFiles(input.toPath());
        remapper.readInputs(input.toPath());

        remapper.readClassPath(mappings.toPath());

        remapper.apply(outputConsumer);
        outputConsumer.close();
        remapper.finish();
    }
}
