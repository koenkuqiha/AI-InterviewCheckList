import java.lang.reflect.Method;

/**
 * 演示入口：
 * 1. 证明系统类加载器在 classpath 中找不到 SampleService（lib/ 未加入 classpath）
 * 2. 用自定义加载器加载并反射实例化，打印"哪个加载器加载的"
 * 3. 用两个不同的自定义加载器实例加载同一类，对比 Class 对象是否相同（命名空间隔离）
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // 类文件根目录，可通过第一个命令行参数覆盖，默认 "lib"
        String libDir = args.length > 0 ? args[0] : "lib";
        System.out.println("===== 自定义 ClassLoader 演示 =====");
        System.out.println("类文件目录: " + libDir + "（运行时工作目录下的相对路径）\n");

        // ---- 1. 系统加载器找不到（双亲委派：父加载器先尝试，找不到才轮到自定义）----
        try {
            Class.forName("com.demo.SampleService");
            System.out.println("[系统加载器] 竟然加载到了 —— 说明 lib 被加进了 classpath，演示失效！");
        } catch (ClassNotFoundException e) {
            System.out.println("[系统加载器] 找不到 com.demo.SampleService（符合预期：lib 不在 classpath）");
        }

        // ---- 2. 自定义加载器加载 ----
        DemoClassLoader loader1 = new DemoClassLoader(libDir);
        System.out.println("\n自定义加载器 loader1 = " + loader1);
        System.out.println("  loader1.parent = " + loader1.getParent() + "  （默认系统类加载器）");

        Class<?> c1 = loader1.loadClass("com.demo.SampleService");
        System.out.println("  c1 由谁加载: " + c1.getClassLoader());

        // 反射实例化并调用（避免 Main 对 SampleService 产生编译期依赖，否则会被系统加载器抢先加载）
        Object obj1 = c1.getDeclaredConstructor().newInstance();
        Method hello = c1.getMethod("hello");
        hello.invoke(obj1);
        Method who = c1.getMethod("whoLoadedMe");
        System.out.println("  whoLoadedMe() 返回: " + who.invoke(obj1));

        // ---- 3. 第二个加载器实例加载同一类，验证命名空间隔离 ----
        DemoClassLoader loader2 = new DemoClassLoader(libDir);
        Class<?> c2 = loader2.loadClass("com.demo.SampleService");
        System.out.println("\n自定义加载器 loader2 加载的 c2 由谁加载: " + c2.getClassLoader());
        System.out.println("c1 == c2 ? " + (c1 == c2)
                + "  （不同加载器实例 => JVM 视为不同类，即使字节码完全相同）");
    }
}
