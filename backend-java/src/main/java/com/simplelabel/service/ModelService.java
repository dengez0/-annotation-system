package com.simplelabel.service;

import com.simplelabel.config.ApiException;
import com.simplelabel.config.AppPaths;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class ModelService {
    private static final Set<String> EXTENSIONS = Set.of(".pt", ".onnx", ".engine");
    private final AppPaths paths;

    public ModelService(AppPaths paths) { this.paths = paths; }

    public List<String> list() throws IOException {
        try (Stream<Path> stream = Files.list(paths.models())) {
            return stream.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(ModelService::supported).sorted().toList();
        }
    }

    public String save(MultipartFile upload, String customName) throws IOException {
        if (upload == null || upload.isEmpty() || upload.getOriginalFilename() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No selected file");
        }
        String original = AnnotationService.fileName(upload.getOriginalFilename());
        String extension = extension(original);
        if (!EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unsupported model extension");
        String name = original;
        if (customName != null && !customName.isBlank()) {
            name = AnnotationService.fileName(customName.trim());
            String customExtension = extension(name);
            if (customExtension.isBlank()) name += extension;
            else if (!customExtension.equalsIgnoreCase(extension)) throw new IllegalArgumentException("Extension mismatch. Please keep " + extension);
        }
        Path destination = paths.safeModelPath(name), temporary = Files.createTempFile(paths.models(), ".model-", ".tmp");
        try { upload.transferTo(temporary); Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
        return name;
    }

    public void delete(String name) throws IOException {
        name = AnnotationService.fileName(name);
        Path model = paths.safeModelPath(name);
        if (!Files.exists(model)) throw new ApiException(HttpStatus.NOT_FOUND, "Model not found");
        Files.delete(model);
        String stem = AnnotationService.stem(name);
        Files.deleteIfExists(paths.models().resolve(stem + ".txt"));
        Files.deleteIfExists(paths.models().resolve(stem + ".yaml"));
    }

    private static boolean supported(String name) { String lower = name.toLowerCase(Locale.ROOT); return EXTENSIONS.stream().anyMatch(lower::endsWith); }
    private static String extension(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? "" : name.substring(dot); }
}
