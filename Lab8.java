import akka.actor.*;
import akka.routing.RoundRobinPool;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Messages {
    static class StartProcessing {
        final List<Path> files;

        StartProcessing(List<Path> files) {
            this.files = files;
        }
    }

    static class MapTask {
        final Path file;

        MapTask(Path file) {
            this.file = file;
        }
    }

    static class MapResult {
        final Map<String, Set<String>> partialResult;

        MapResult(Map<String, Set<String>> partialResult) {
            this.partialResult = partialResult;
        }
    }

    static class ReduceResult {
        final Map<String, Set<String>> globalIndex;

        ReduceResult(Map<String, Set<String>> globalIndex) {
            this.globalIndex = globalIndex;
        }
    }

    static class Shutdown {}
}

class Mapper extends AbstractActor {
    private final Pattern pattern = Pattern.compile(
            "\\b(class|interface)\\b\\s+(\\w+)" +
                    "(?:\\b\\s+extends\\s+([\\w\\.]+))?" +
                    "(?:\\b\\s+implements\\s+([\\w\\.,\\s]+))?");

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Messages.MapTask.class, this::processMapTask)
                .match(Messages.Shutdown.class, msg -> getContext().stop(getSelf()))
                .build();
    }

    private void processMapTask(Messages.MapTask task) {
        Map<String, Set<String>> partial = new HashMap<>();

        try {
            String code = Files.readString(task.file);
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
            System.err.println("Ошибка чтения файла " + task.file + ": " + e.getMessage());
        }

        getSender().tell(new Messages.MapResult(partial), getSelf());
    }
}

class Reducer extends AbstractActor {
    private Map<String, Set<String>> globalIndex = new HashMap<>();
    private int resultsReceived = 0;
    private int totalTasks;
    private ActorRef originalSender;
    private long startTime;

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Messages.MapResult.class, this::reduceResult)
                .match(InitializeReducer.class, this::initialize)
                .match(Messages.Shutdown.class, msg -> getContext().stop(getSelf()))
                .build();
    }

    static class InitializeReducer {
        final int totalTasks;
        final ActorRef originalSender;
        final long startTime;

        InitializeReducer(int totalTasks, ActorRef originalSender, long startTime) {
            this.totalTasks = totalTasks;
            this.originalSender = originalSender;
            this.startTime = startTime;
        }
    }

    private void initialize(InitializeReducer init) {
        this.totalTasks = init.totalTasks;
        this.originalSender = init.originalSender;
        this.startTime = init.startTime;
    }

    private void reduceResult(Messages.MapResult result) {
        for (Map.Entry<String, Set<String>> entry : result.partialResult.entrySet()) {
            globalIndex.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                    .addAll(entry.getValue());
        }

        resultsReceived++;

        if (resultsReceived % 100 == 0) {
            System.out.printf("Reducer получил результатов: %d / %d%n",
                    resultsReceived, totalTasks);
        }

        if (resultsReceived == totalTasks) {
            System.out.println("\nФинальные результаты:");
            globalIndex.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> System.out.println(e.getKey() + " -> " +
                            String.join(", ", e.getValue())));

            long totalImplementations = globalIndex.values().stream()
                    .mapToInt(Set::size)
                    .sum();
            System.out.println("\nВсего реализаций: " + totalImplementations);

            long endTime = System.nanoTime();
            System.out.printf("Общее время: %.3f s%n", (endTime - startTime) / 1e9);

            originalSender.tell(new Messages.ReduceResult(globalIndex), getSelf());
            getContext().stop(getSelf());
        }
    }
}

class MapReduceMaster extends AbstractActor {
    private final int numMappers;
    private ActorRef mapperRouter;
    private ActorRef reducer;
    private int tasksSent = 0;

    public MapReduceMaster(int numMappers) {
        this.numMappers = numMappers;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Messages.StartProcessing.class, this::startProcessing)
                .match(Messages.ReduceResult.class, this::processingComplete)
                .match(Messages.Shutdown.class, msg -> getContext().stop(getSelf()))
                .build();
    }

    private void startProcessing(Messages.StartProcessing msg) {
        System.out.println("Найдено файлов: " + msg.files.size());

        // Создаем роутер для мапперов
        mapperRouter = getContext().actorOf(
                new RoundRobinPool(numMappers).props(Props.create(Mapper.class)),
                "mapperRouter"
        );

        // Создаем редьюсер
        reducer = getContext().actorOf(Props.create(Reducer.class), "reducer");

        // Инициализируем редьюсер
        reducer.tell(new Reducer.InitializeReducer(
                msg.files.size(),
                getSelf(),
                System.nanoTime()
        ), getSelf());

        // Распределяем задачи по мапперам
        for (Path file : msg.files) {
            mapperRouter.tell(new Messages.MapTask(file), reducer);
            tasksSent++;
        }
    }

    private void processingComplete(Messages.ReduceResult result) {
        System.out.println("Обработка завершена!");

        // Отправляем shutdown мапперам
        for (int i = 0; i < numMappers; i++) {
            mapperRouter.tell(new Messages.Shutdown(), getSelf());
        }

        // Завершаем работу
        getContext().stop(getSelf());
    }
}

public class AkkaMapReduce {
    public static void main(String[] args) throws IOException {
        Path root = Paths.get("spring-framework");
        final int numMappers = 16;

        // Собираем все .java файлы
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).toList();
        }

        // Создаем акторную систему
        ActorSystem system = ActorSystem.create("MapReduceSystem");

        // Создаем Future для получения результата
        var resultFuture = new scala.concurrent.SyncVar<Messages.ReduceResult>();

        // Создаем актор для получения результата
        ActorRef resultReceiver = system.actorOf(
                Props.create(ResultCollector.class, resultFuture),
                "resultCollector"
        );

        // Создаем мастер-актор
        ActorRef master = system.actorOf(
                Props.create(MapReduceMaster.class, numMappers),
                "master"
        );

        // Запускаем обработку
        master.tell(new Messages.StartProcessing(files), resultReceiver);

        // Ждем результат
        Messages.ReduceResult result = resultFuture.get();

        // Завершаем систему
        system.terminate();
    }

    // Актор для сбора результата
    static class ResultCollector extends AbstractActor {
        private final scala.concurrent.SyncVar<Messages.ReduceResult> resultFuture;

        public ResultCollector(scala.concurrent.SyncVar<Messages.ReduceResult> resultFuture) {
            this.resultFuture = resultFuture;
        }

        @Override
        public Receive createReceive() {
            return receiveBuilder()
                    .match(Messages.ReduceResult.class, msg -> {
                        resultFuture.put(msg);
                        getContext().stop(getSelf());
                    })
                    .build();
        }
    }
}
