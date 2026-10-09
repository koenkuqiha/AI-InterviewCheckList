import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

/**
 * 案例一：最经典的 ABA —— 无锁栈取出错误元素
 *
 * 核心机理：
 *   CAS（Compare-And-Swap）只比较「值是否相同」。
 *   ABA 问题：值 A → B → A，最终没变；CAS 判定「没人修改过，可以安全写入」，
 *   但实际上这个值已经被换成了另一个对象又换回来了 —— CAS 无从感知。
 *
 * 关键说明：
 *   本例每次 push 都创建一个「新节点对象」，这是必须的。
 *   如果复用同一个节点对象（nodeB 被push 两次），它的 next 指针会自指成环，
 *   那是另一个 bug，不是 ABA，别混淆。
 */
public class AbaBasic {

    /** 栈节点：不可变，next 在构造时定死（避免复用导致的环） */
    static final class Node {
        final int value;
        final Node next;

        Node(int value, Node next) {
            this.value = value;
            this.next = next;
        }
    }

    /** 无锁栈：用 AtomicReference 存栈顶 */
    static final class LockFreeStack {
        private final AtomicReference<Node> top = new AtomicReference<>(null);

        /** push 创建一个新节点，next 指向当前栈顶 */
        void push(int value) {
            Node oldTop;
            Node newNode;
            do {
                oldTop = top.get();
                newNode = new Node(value, oldTop);
            } while (!top.compareAndSet(oldTop, newNode));
        }

        Node pop() {
            while (true) {
                Node oldTop = top.get();
                if (oldTop == null) return null;
                Node newTop = oldTop.next;
                if (top.compareAndSet(oldTop, newTop)) {
                    return oldTop;
                }
            }
        }

        Node peek() {
            return top.get();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== 案例一：经典 ABA —— 无锁栈取出错误元素 ==========\n");

        LockFreeStack stack = new LockFreeStack();
        stack.push(1);                       // 压入 A（值 1）
        stack.push(2);                       // 压入 B（值 2），栈：顶->2->1

        Node t = stack.peek();
        System.out.println("初始栈：顶-> " + t.value + " -> " + t.next.value);

        CountDownLatch start = new CountDownLatch(1);

        // 线程 1：pop 出 B，然后做耗时业务，最后把 B 压回
        Thread t1 = new Thread(() -> {
            try {
                start.await();
                Node got = stack.pop();                  // CAS成功，取出 B（值2），栈顶变A
                System.out.println("[线程1] 取出元素：" + got.value + "，此时栈顶是 A("
                        + stack.peek().value + ")");

                Thread.sleep(300);                       // 模拟耗时业务处理

                stack.push(got.value);                   // 压回 B，期望 CAS(A, newB) 成功
                System.out.println("[线程1] 业务完成，压回 " + got.value);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "线程1");

        // 线程 2：在「线程1 取出」之后、「线程1 压回」之前，
        // 把栈顶 A 弹掉、又压回 A —— 值完成了 A→空→A 的绕圈（ABA 的等价形式）
        Thread t2 = new Thread(() -> {
            try {
                start.await();
                Thread.sleep(100);                        // 等线程1 先取出

                Node stole = stack.pop();                 // 取出 A（栈空）
                System.out.println("[线程2] 偷取出元素：" + stole.value + "，此时栈空");

                stack.push(99);                           // 压入 99：空->99
                System.out.println("[线程2] 压入 99   ← 此刻栈顶从 A 变成了 99（A→99）");

                Thread.sleep(200);                        // 关键窗口：等线程1 执行 CAS
                stack.push(1);                            // 再压回 1：99->A（99→1，绕回原值）
                System.out.println("[线程2] 压回 1     ← 此刻栈顶从 99 变回 1（99→1，回到原值）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "线程2");

        t1.start();
        t2.start();
        start.countDown();

        t1.join();
        t2.join();

        System.out.println("\n最终栈（最多打印 10 个）：");
        Node cur = stack.pop();
        int count = 0;
        while (cur != null && count < 10) {
            System.out.println("  -> " + cur.value);
            cur = stack.pop();
            count++;
        }
        if (cur != null) System.out.println("  ...（还有更多）");

        System.out.println("\n【原理】CAS 只比较值，不比较对象身份。");
        System.out.println("       线程1 读取栈顶 A(值1)，准备写回 B 时，");
        System.out.println("       值已经 A(1)→99→1 绕了一圈，CAS 认为『没人动过』，于是成功写入。");
        System.out.println("       这就是 ABA —— 判定与实际不一致，造成栈里元素数量/顺序错乱。");
    }
}