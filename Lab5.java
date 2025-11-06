import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {

        List<Path> files;
        Map<String, Set<String>> classes = new HashMap<>();

        try (Stream<Path> stream = Files.walk(Paths.get("spring-framework"))) {
            files = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }

        System.out.println("Количество файлов " + files.size());

        ExecutorService executor = Executors.newFixedThreadPool(1000);
        List<Future<Map<String, Set<String>>>> futures = new ArrayList<>();

        Pattern pattern = Pattern.compile(
                "\\b(class|interface)\\b\\s+(\\w+)" +
                        "(?:\\b\\s+extends\\s+([\\w\\.]+))?" +
                        "(?:\\b\\s+implements\\s+([\\w\\.,\\s]+))?");

        for (Path path: files){
            Callable<Map<String, Set<String>>> task = () -> {
                Map<String, Set<String>> part = new HashMap<>();
                try{
                    String code = Files.readString(path);

                    code = code.replaceAll("(?s)/\\*.*?\\*/", "");
                    code = code.replaceAll("(?m)//.*?$", "");

                    Matcher m = pattern.matcher(code);
                    while (m.find()){
                        String className = m.group(2);
                        String extendsPart = m.group(3);
                        String implementsPart = m.group(4);

                        if (extendsPart != null) {
                            part.computeIfAbsent(extendsPart, k -> ConcurrentHashMap.newKeySet()).add(className);
                        }

                        if (implementsPart != null) {
                            part.computeIfAbsent(implementsPart, k -> ConcurrentHashMap.newKeySet()).add(className);
                        }
                    }
                } catch (IOException e){
                    System.out.println(e.getMessage());
                }
                return part;
            };
            Future<Map<String, Set<String>>> future = executor.submit(task);
            futures.add(future);
        }

        for (Future<Map<String, Set<String>>> future : futures){
            try{
                Map<String, Set<String>> partial = future.get();
                for (Map.Entry<String, Set<String>> e : partial.entrySet()) {
                    classes.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(e.getValue());
                }
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
        executor.shutdown();

        classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(e.getKey() + " -> " + e.getValue() + " size -> " + e.getValue().size()));
    }

}
