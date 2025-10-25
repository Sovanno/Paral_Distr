import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {

        List<Path> files;
        ConcurrentMap<String, Set<String>> classes = new ConcurrentHashMap<>();

        try (Stream<Path> stream = Files.walk(Paths.get("spring-framework"))) {
            files = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }

        CountDownLatch latch = new CountDownLatch(files.size());
        List<Thread> threads = new ArrayList<>(files.size());

        System.out.println("Количество файлов" + files.size());

        Pattern pattern = Pattern.compile(
                "\\b(class|interface)\\b\\s+(\\w+)" +
                        "(?:\\b\\s+extends\\s+([\\w\\.]+))?" +
                        "(?:\\b\\s+implements\\s+([\\w\\.,\\s]+))?");

        for (Path path: files){
            Thread t = new Thread(() -> {
                try {
                    String code = Files.readString(path);

                    code = code.replaceAll("(?s)/\\*.*?\\*/", "");
                    code = code.replaceAll("(?m)//.*?$", "");

                    Matcher m = pattern.matcher(code);

                    while (m.find()){
                        String className = m.group(2);
                        String extendsPart = m.group(3);
                        String implementsPart = m.group(4);

                        if (extendsPart != null) {
                            classes.computeIfAbsent(extendsPart, k -> ConcurrentHashMap.newKeySet()).add(className);
                        }

                        if (implementsPart != null) {
                            classes.computeIfAbsent(implementsPart, k -> ConcurrentHashMap.newKeySet()).add(className);
                        }
                    }
                } catch (IOException e){
                    System.out.println(e.getMessage());
                } finally {
                    latch.countDown();
                }
            });

            threads.add(t);
            t.start();
        }

        latch.await();

         classes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(e.getKey() + " -> " + e.getValue() + " size -> " + e.getValue().size()));
    }

}
