/**
 * 场景 5：java.lang.OutOfMemoryError: Requested array size exceeds VM limit
 *
 * 成因：代码里硬编码了一个荒谬的数组长度。与堆是否够用无关——
 *       即使你给 64G 堆，new int[2147483647] (约 8GB) 只要超过 MaxHeapSize 就直接报这个错。
 *
 * 特点：
 *   1. 通常是【数组长度声明越界/计算错误】，不是内存真的不足
 *   2. 报错发生在【分配之前】，不会真正消耗内存，所以不会有 GC 停顿
 *   3. 常见来源：int overflow (int * 2 溢出成负数)、分页 size 被传入超大值、
 *      文件长度计算错误 (length * 2 溢出)
 *
 * 运行参数：无（任何配置都能复现）
 * IDEA 设置：直接右键 Run Demo5_ArraySizeLimit
 */
public class Demo5_ArraySizeLimit {

    public static void main(String[] args) {
        System.out.println("=== 复现 Requested array size exceeds VM limit ===\n");

        // 复现方式 1：直接声明一个荒谬的数组（最直观的演示）
        System.out.println("[复现1] 直接申请 int[Integer.MAX_VALUE] = 约 8GB");
        try {
            int[] huge = new int[Integer.MAX_VALUE];
            System.out.println("  意外：没报错, length=" + huge.length);
        } catch (OutOfMemoryError e) {
            System.out.println("  捕获到: " + e.getClass().getName());
            System.out.println("  message: " + e.getMessage());
        }

        // 复现方式 2：int 溢出导致长度为负 (生产上极常见的隐蔽 bug)
        System.out.println("\n[复现2] int 溢出导致长度为负 (生产隐蔽 bug)");
        try {
            int count = 1_500_000_000;              // 想分配 15 亿个元素
            int size = count * 2;                  // 乘法溢出成负数！
            System.out.println("  count*2 = " + size + " (溢出为负)");
            byte[] buf = new byte[size];           // length < 0
            System.out.println("  意外：没报错, length=" + buf.length);
        } catch (OutOfMemoryError e) {
            System.out.println("  捕获到: " + e.getClass().getName());
            System.out.println("  message: " + e.getMessage());
        } catch (NegativeArraySizeException e) {
            System.out.println("  注意：某些路径会先抛 NegativeArraySizeException");
            System.out.println("  两者都是【同一个根因】：长度计算出了错，而不是内存不足。");
        }

        System.out.println("\n--- 面试要点 ---");
        System.out.println("Requested array size exceeds VM limit 与 Java heap space 的本质区别：");
        System.out.println("  - heap space   : 内存【真的不够】，加内存可能缓解；");
        System.out.println("  - exceeds VM limit : 分配的【长度本身非法/超限】，加内存也没用，必须改代码。");
        System.out.println("  - 数组最大长度受 JVM 参数限制，-Xmx 512m 时 int[] 最多约 1.28 亿个元素；");
        System.out.println("    int[] 理论最大 Integer.MAX_VALUE，但会先被 MaxHeapSize 卡住。");
    }
}