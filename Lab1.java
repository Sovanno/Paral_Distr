import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) throws IOException {
        System.out.println();
        String filePath = "text.txt";
        String text = Files.readString(Paths.get(filePath));
        
        text = text.toLowerCase();
        
        Pattern pattern = Pattern.compile("[\\W\\s]");
        String[] words = pattern.split(text);
        
        Map<String, Integer> dictionary = new HashMap<String,Integer>();
        for(String word: words){
            if(Objects.equals(word, "")){
                continue;
            }
            if(Objects.equals(dictionary.get(word), null)){
                dictionary.put(word, 1);
            }
            else {
                dictionary.put(word, dictionary.get(word)+1);
            }
        }
        for(String key: dictionary.keySet()){
            System.out.println(key + " = " + dictionary.get(key));
        }
      
    }
}
