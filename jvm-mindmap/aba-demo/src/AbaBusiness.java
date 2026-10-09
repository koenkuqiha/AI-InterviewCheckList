import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;

/**
 * 案例二：业务场景中的 ABA（更贴近真实 bug）
 *
 * 场景：账户余额扣减。用 AtomicLong 存余额，
 *      扣减时用 CAS 循环「只要余额还是我读到的那个数，就减去100」。
 *
 * bug 场景：
 *   线程1 读到余额=1000，准备 CAS(1000, 900)
 *   线程2 又改了两次余额：1000 → 2000（其他操作加钱）→ 1000（又扣掉）
 *   线程1 的 CAS 认为「余额还是 1000，没人动过」，成功扣款 —— 判定错误
 *
 * 这个场景比无锁栈更贴近面试和真实业务，
 * 也更容易说明「ABA 在业务层」意味着什么：数据错乱、资金对不上账。
 */
public class AbaBusiness {

    /** 账户服务：用 AtomicLong 做扣减 */
    static class AccountService {
        private final AtomicLong balance = new AtomicLong(1000);

        /** 错误示范：用余额值做 CAS 依据 → 有 ABA 风险 */
        void withdrawWrong(long amount) {
            while (true) {
                long old = balance.get();               // 读当前余额
                if (old < amount) throw new IllegalStateException("余额不足");
                long target = old - amount;
                // 这里仅用「old 余额值」做 CAS 依据 —— ABA 问题根源
                if (balance.compareAndSet(old, target)) {
                    System.out.println("  [错误示范] 扣款成功：" + old + " -> " + target);
                    return;
                }
            }
        }

        long get() {
            return balance.get();
        }
    }

    /** 正确做法一：加版本戳 AtomicStampedReference */
    static class AccountServiceWithStamp {
        private final AtomicStampedReference<Long> balance =
                new AtomicStampedReference<Long>(1000L, 0);

void withdraw(long amount) {
            while (true) {
                // 【关键】必须用 get(int[]) 一次读出「值 + 版本号」，且拿到的是已存在的对象引用
                int[] stampHolder = new int[1];
                Long old = balance.get(stampHolder);   // 返回已存在的 Long 引用（不要拆箱再装箱）
                int version = stampHolder[0];
                if (old < amount) throw new IllegalStateException("余额不足");
                Long target = old - amount;            // 同样保持为Long 引用
                // CAS 内部按引用比较，所以传的对象必须和读取时是同一个引用
                if (balance.compareAndSet(old, target, version, version + 1)) {
                    System.out.println("  [版本号解法] 扣款成功：" + old + " -> " + target
                            + "（版本 " + version + " -> " + (version + 1) + "）");
                    return;
                }
            }
        }

        long get() {
            return balance.getReference();
        }
    }

    /** 正确做法二：每次操作让值「不可能回到原样」 —— 附加唯一递增序号 */
    static class AccountServiceWithSeq {
        // 把「余额 + 全局序号」打包成一个 long，高位存序号，低位存余额
        // 每次操作序号必变，即使余额回到同样的数字，包装后的值也不同
        private final AtomicLong packed = new AtomicLong(pack(1000, 0));

        static long pack(long balance, long seq) {
            return (seq << 32) | (balance & 0xFFFFFFFFL);
        }

        void withdraw(long amount) {
            while (true) {
                long cur = packed.get();
                long seq = cur >> 32;
                long balance = cur & 0xFFFFFFFFL;
                if (balance < amount) throw new IllegalStateException("余额不足");
                long target = pack(balance - amount, seq + 1);   // 序号 +1，值不会「绕回来」
                if (packed.compareAndSet(cur, target)) {
                    System.out.println("  [序号解法] 扣款成功：余额 " + balance + " -> " + (balance - amount)
                            + "，序号 " + seq + " -> " + (seq + 1));
                    return;
                }
            }
        }

        long get() {
            return packed.get() & 0xFFFFFFFFL;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("========== 案例二：业务场景中的 ABA（账户余额错乱） ==========\n");

        // ---------- 演示错误示范 ----------
        System.out.println("【错误示范】只用余额值做 CAS 依据：");
        AccountService wrong = new AccountService();
        CountDownLatch start = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            try {
                start.await();
                wrong.withdrawWrong(100);               // 读余额=1000, CAS(1000->900)
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }, "扣款线程1");

        Thread t2 = new Thread(() -> {
            try {
                start.await();
                Thread.sleep(200);                       // 让t1 先读到余额
                // t2 把余额 A→B→A 绕一圈：先 +1000，再 -1000，余额回到 1000
                wrong.get();
                Thread.sleep(50);
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        }, "干扰线程2");

        t1.start();
        t2.start();
        start.countDown();
        t1.join();
        t2.join();

        System.out.println("  最终余额：" + wrong.get() + "（1000 是初始值）");
        System.out.println("  【问题】t1 的 CAS 认为余额没被别人动过，但实际中间被改过两次。");
        System.out.println("          如果余额恰好绕回原值，CAS 就无法察觉 -> 资金对不上账。\n");

        // ---------- 解法一：版本戳 ----------
        System.out.println("【解法一】AtomicStampedReference 加版本戳：");
        AccountServiceWithStamp fix1 = new AccountServiceWithStamp();
        fix1.withdraw(100);
        fix1.withdraw(100);
        System.out.println("  最终余额：" + fix1.get() + "\n");

        // ---------- 解法二：附加唯一序号 ----------
        System.out.println("【解法二】打包「余额+唯一序号」，让值不可能绕回原样：");
        AccountServiceWithSeq fix2 = new AccountServiceWithSeq();
        fix2.withdraw(100);
        fix2.withdraw(100);
        System.out.println("  最终余额：" + fix2.get());

        System.out.println("\n【核心思想】");
        System.out.println("  ABA 的本质不是「值变了又变回来」，而是「CAS 无法感知中间发生过变更」。");
        System.out.println("  解法就两条路：");
        System.out.println("    1) 引入额外的版本戳/序号，一旦变更必定改变 —— AtomicStampedReference、LongAdder；");
        System.out.println("    2) 让被CAS 的值本身『不可能回到原样』—— 加全局唯一序号、时间戳、版本号。");
        System.out.println("  另外，Java 里某些场景天然规避了 ABA：");
        System.out.println("    - LongAdder：分段累加，不做「读-改-写」的 CAS 循环，规避了这个问题；");
        System.out.println("    - 对象池复用（如线程池的 Thread 对象）不会 ABA，因为复用后引用值本身不变。");
    }
}