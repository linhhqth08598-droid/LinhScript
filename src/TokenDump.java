import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.hql.script.Lexer;

public class TokenDump {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: TokenDump <file.hql>");
            return;
        }

        String content = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        // Không cần replace \n thành ; nữa vì Lexer mới đã tự hiểu dấu xuống dòng
        content = content.replace("\r", "");

        // Cập nhật tham số thứ 2 (tên file) cho hàm tokenize
        var tokens = Lexer.tokenize(content, args[0]);

        for (int i = 0; i < tokens.size(); i++) {
            System.out.println(i + ": " + tokens.get(i));
        }
    }
}