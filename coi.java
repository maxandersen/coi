///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
//DEPS io.quarkus.platform:quarkus-bom:3.16.1@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkiverse.langchain4j:quarkus-langchain4j-ollama:0.21.0

//Q:CONFIG quarkus.banner.enabled=false
////Q:CONFIG quarkus.log.level=WARN
//Q:CONFIG quarkus.langchain4j.ollama.chat-model.model-id=granite3-dense

//Q:CONFIG quarkus.langchain4j.temperature=0.2
//Q:CONFIG quarkus.langchain4j.timeout=60s
////Q:CONFIG quarkus.langchain4j.log-requests=true
////Q:CONFIG quarkus.langchain4j.log-responses=true

//Q:CONFIG quarkus.log.level=WARN
//Q:CONFIG quarkus.log.category."coi".level=DEBUG

import static java.lang.System.out;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command
public class coi implements Callable<Integer> {

    @ConfigProperty(name = "quarkus.langchain4j.ollama.chat-model.model-id")
    private String modelName;

    @Inject
    private ChatLanguageModel podmanai;

    @Option(names = { "--input", "-i" }, description = "Directory with input files", defaultValue = "input")
    private Path inputDir;

    @Option(names = { "-o", "--output" }, description = "Output directory", defaultValue = "output")
    private Path outputDir;

    @Option(names = { "--model-dir" }, description = "Sub dir per model", defaultValue = "true")
    private boolean appendModelName;

    @Override
    public Integer call() throws IOException {
        pullModel(modelName);

        Files.list(inputDir)
                .filter(file -> !file.getFileName().toString().endsWith(".result"))
                .forEach(file -> process(file));

        return 0;
    }

    private void process(Path file) {
        Log.info("Processing " + file);
        try {
            String code = readString(file);

            String result = null;
            Path resultFile = file.resolveSibling(file.getFileName().toString().replaceAll("\\.[^.]+$", ".result"));
            Log.info("Checking for result file " + resultFile);
            if (exists(resultFile)) {
                result = readString(resultFile);
            }
            var response = generateResponse(code);

            Path outDir = outputDir;
            if (appendModelName) {
                outDir = outputDir.resolve(modelName);
            }
            var outputFilePath = outDir.resolve(file.getFileName());

            createDirectories(outputFilePath.getParent());
            Log.info("Writing to " + outputFilePath);
            writeString(outputFilePath, response);

            if (result != null) {
                Log.info("Comparing result with " + result);
                var handlers = Map.of("java", "jbang", "js", "node", "py", "python3");

                var name = outputFilePath.getFileName().toString();
                String suffix = name.substring(name.indexOf(".") + 1);

                var cmd = handlers.get(suffix);

                if (cmd != null) {
                    checkResult(outputFilePath, cmd, result);
                } else {
                    Log.warn("No result handler for " + file);
                }
            }

        } catch (IOException e) {
            throw new IllegalStateException("Failed to process file", e);
        }
    }

    boolean checkResult(Path sourcePath, String command, String expected) {
        List<String> commandParts = List.of(command, sourcePath.toString());
        Log.info("Running %s".formatted(commandParts));
        try {
            ProcessBuilder pb = new ProcessBuilder(commandParts);
            Process process = pb.start();

            // Capture stdout
            String realResult = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                realResult = reader.lines().collect(Collectors.joining("\n"));
            }

            // Capture stderr
            String error = "";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                error = reader.lines().collect(Collectors.joining("\n"));
            }


            process.waitFor();
            Log.debug("realResult '%s'".formatted(realResult));
            boolean correct = realResult.equals(expected);

            Files.writeString(
                 sourcePath.resolveSibling(sourcePath.getFileName() + (correct?".result":".badresult")),
                 realResult);

            Log.debug("error '%s'".formatted(error));
            if (error != null && !error.isBlank()) {
                Files.writeString(
                        sourcePath.resolveSibling(sourcePath.getFileName() + ".stderr"),
                        error);
            }
            if (correct) {
                Log.info("Result is correct");
                return true;
            } else {
                Log.error("Result is incorrect '%s'".formatted(realResult));
                return false;
            }
        } catch (IOException | InterruptedException e) {
            Log.error("Failed to check result", e);
            return false;
        }

    }

    private String generateResponse(String code) {
        var system = SystemMessage.from(
                """
                        You are an experienced software developer that writes clean, efficient, maintainable and readable code.
                        You like to write code that is easy to understand and easy to maintain. When editing code, you always
                        adapt to the style of the code you are editing.

                        * Do not remove comments that are present in the code that is unrelated to the code you are editing.
                        * Do not include any commentary before or after the code in the response. Only the code should be returned.
                        """);

        var message = UserMessage.from(
                """
                        Below is the code being edited. <CURSOR> if present is where users cursor are.
                        Do not include that in the response. Simply return the full code after editing it.
                        Do not include any other text than the code.

                        Code:
                        """
                        + code);
        var response = podmanai.generate(List.of(system, message));
        return response.content().text();
    }

    private void pullModel(String name) {
        try {
            // Check if model already exists
            ProcessBuilder checkBuilder = new ProcessBuilder("ollama", "show", name);
            // checkBuilder.inheritIO();
            Process checkProcess = checkBuilder.start();

            if (checkProcess.waitFor() == 0) {
                // Model exists, no need to pull
                // System.out.println("Model " + name + " already exists");
                return;
            }
            out.println("Running command: ollama pull " + name);
            ProcessBuilder processBuilder = new ProcessBuilder("ollama", "pull", name);
            processBuilder.inheritIO();
            Process process = processBuilder.start();

            // Wait for process to complete
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("Failed to pull model. Exit code: " + exitCode);
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed to pull model", e);
        }
    }
}
