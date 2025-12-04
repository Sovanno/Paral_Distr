import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Main {
    private static final Path POISON_PILL = Paths.get("POISON_PILL");
    private static final Map<String, Set<String>> COLLECTOR_PILL = Collections.emptyMap();

    public static void main(String[] args) throws IOException, InterruptedException {
        Path root = Paths.get("spring-framework");
        final int numWorkers = 16;

        long startAll = System.nanoTime();
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }
        System.out.println("Найдено файлов: " + files.size());

        BlockingQueue<Path> taskQueue = new LinkedBlockingQueue<>(200);
        BlockingQueue<Map<String, Set<String>>> resultQueue = new LinkedBlockingQueue<>(200);

        Pattern pattern = Pattern.compile(
                "\\b(class|interface)\\b\\s+(\\w+)" +
                        "(?:\\b\\s+extends\\s+([\\w\\.]+))?" +
                        "(?:\\b\\s+implements\\s+([\\w\\.,\\s]+))?");

        Map<String, Set<String>> globalIndex = new HashMap<>();

        Thread collector = new Thread(() -> {
            int count = 0;
            try {
                while (true) {
                    Map<String, Set<String>> partial = resultQueue.take();

                    if (partial == COLLECTOR_PILL) {
                        break;
                    }

                    count++;

                    for (Map.Entry<String, Set<String>> e : partial.entrySet()) {
                        globalIndex
                                .computeIfAbsent(e.getKey(), k -> ConcurrentHashMap.newKeySet())
                                .addAll(e.getValue());
                    }

                    if (count % 100 == 0 || count == files.size()) {
                        System.out.printf("Collector получил: %d / %d%n", count, files.size());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "collector");

        collector.start();

        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < numWorkers; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (true) {
                        Path path = taskQueue.take();
                        if (path.equals(POISON_PILL)) {
                            break;
                        }

                        Map<String, Set<String>> partial = new HashMap<>();
                        try {
                            String code = Files.readString(path);

                            code = code.replaceAll("(?s)/\\*.*?\\*/", "");
                            code = code.replaceAll("(?m)//.*?$", "");

                            Matcher m = pattern.matcher(code);
                            while (m.find()) {
                                String className = m.group(2);
                                String extendsPart = m.group(3);
                                String implementsPart = m.group(4);

                                if (extendsPart != null) {
                                    partial.computeIfAbsent(extendsPart, k -> new HashSet<>()).add(className);
                                }
                                if (implementsPart != null) {
                                    String[] interfaces = implementsPart.split(",");
                                    for (String iface : interfaces) {
                                        String trimmed = iface.trim();
                                        if (!trimmed.isEmpty()) {
                                            partial.computeIfAbsent(trimmed, k -> new HashSet<>()).add(className);
                                        }
                                    }
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("Ошибка чтения файла " + path + ": " + e.getMessage());
                        } finally {
                            resultQueue.put(partial);
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "worker-" + i);
            t.start();
            workers.add(t);
        }

        for (Path p : files) {
            taskQueue.put(p);
        }

        for (int i = 0; i < numWorkers; i++) {
            taskQueue.put(POISON_PILL);
        }

        for (Thread t : workers) {
            t.join();
        }

        resultQueue.put(COLLECTOR_PILL);

        collector.join();

        globalIndex.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(e.getKey() + " -> " + e.getValue() + " size -> " + e.getValue().size()));

        long totalImplementations = globalIndex.values().stream()
                .mapToInt(Set::size)
                .sum();
        System.out.println("Всего реализаций: " + totalImplementations);
        long endAll = System.nanoTime();
        System.out.printf("Общее время: %.3f s%n", (endAll - startAll) / 1e9);
    }
}
