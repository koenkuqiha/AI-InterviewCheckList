import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 自定义类加载器：从指定目录加载 .class 文件。
 *
 * 关键点：
 * 1. 继承 ClassLoader，重写 findClass —— 不要在 loadClass 里硬改双亲委派，
 *    重写 findClass 即可保留双亲委派语义（先问父加载器，父找不到再自己加载）。
 * 2. 用 defineClass 把字节数组变成 Class 对象。
 * 3. parent 默认是系统类加载器（AppClassLoader），这符合双亲委派模型。
 */
public class DemoClassLoader extends ClassLoader {

    /** 类文件根目录（传入的是根，不含包路径前缀）。例如 lib/ ，则 com.demo.X -> lib/com/demo/X.class */
    private final String classDir;

    public DemoClassLoader(String classDir) {
        super(); // parent = 系统类加载器（默认）
        this.classDir = classDir;
    }

    public DemoClassLoader(String classDir, ClassLoader parent) {
        super(parent);
        this.classDir = classDir;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // name 形如 "com.demo.SampleService"
        String relative = name.replace('.', '/') + ".class";
        Path path = Paths.get(classDir, relative);

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ClassNotFoundException("自定义加载器找不到类文件: " + path, e);
        }

        System.out.println("[DemoClassLoader] 从磁盘读取并 defineClass: " + path
                + " (" + bytes.length + " 字节)");

        // 第四个参数 protectionDomain 传 null，使用默认保护域
        return defineClass(name, bytes, 0, bytes.length);
    }
}
