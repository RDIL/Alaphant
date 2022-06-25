package alaphant.build;

import alaphant.build.helpers.MappingAnalysisHelper;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Task;
import org.gradle.api.tasks.InputFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

public class AnalyzeMappingsTask extends DefaultTask {
    @InputFile
    private File mappings;

    public File getMappings() {
        return mappings;
    }

    public void setMappings(@NotNull final File mappingsFile) {
        this.mappings = mappingsFile;
        this.getInputs().file(mappingsFile);
    }

    @SuppressWarnings("Convert2Lambda")
    public AnalyzeMappingsTask() {
        this.setGroup("mappings");
        this.setDescription("Analyze mappings file for completion statistics.");

        this.doLast(new Action<>() {
            @Override
            public void execute(@NotNull final Task task) {
                try {
                    final MappingAnalysisHelper analysisHelper = new MappingAnalysisHelper();

                    MappingReader.read(AnalyzeMappingsTask.this.getMappings().toPath(), MappingFormat.TINY, analysisHelper);

                    analysisHelper.logResults(task.getLogger());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
