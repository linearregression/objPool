An object pool that utilizes the {@link com.lmax.disruptor.RingBuffer} from the LMAX Disruptor framework. Typically a producer thread will borrow objects from the object pool and consumer threads will then return the borrowed objects to the object pool. Object pools are most commonly implemented using a lock. A lock is required to ensure mutual exclusion and visibility of changes to the underlying data structure amongst readers(producers) and writers(consumers).

The problem with this approach is that a shared lock has to be acquired and released every time an object is borrowed from the object pool. The shared lock must again be acquired and released to return a borrowed object to the object pool. This creates a central point of contention amongst readers and writers (the lock) and greatly reduces performance. When two different threads try to acquire a lock at the same time the operating system must step in and perform arbitration to decide which thread will receive the lock, and which will not. The operating system may also perform its own internal housekeeping tasks while settling this dispute. This housekeeping introduces performance degradations as work is being performed that is not relevant to the underlying threads' planned operation.

Another important consideration is what to do when a producer thread outpaces the consumer threads. In a garbage collected language such as Java, the implementer has 3 choices: produce no garbage, some garbage, or a lot of garbage. I'm an American so I prefer to create a lot of garbage. This object pool attempts to give users the option to produce no garbage or some garbage. Depending on the configuration of {@link DisruptorObjectPool#poolSize} and {@link DisruptorObjectPool#createGarbage} a lot of garbage may inadvertently get created. It is important to choose very carefully according to the needs of problem being solved.

There are 5 very important constraints that must be enforced to ensure correct operation of the object pool:

1. All objects in the object pool must be created via {@link DisruptorObjectPool#createNewObject(DisruptorObjectPool)}
2. Only 1 thread must ever attempt to borrow objects from the object pool.
3. All pooled objects must implement the {@link Poolable} interface
4. Consumer threads must never maintain a reference to a pooled object after returning an object to the pool
5. If the object pool is configured to create no garbage, then the pooled objects must be returned to the object pool in a timely fashion.

Constraint 1 is imposed to pre-create objects at object pool construction time. Additionally an object pool may be configured to create garbage instead of waiting for an object to be returned to the pool. This method will be called in both cases.

Constraint 2 is imposed to ensure correct behavior in a multi-threading environment. Since objects are being removed and replaced from the pool the single writer principle must be enforced for the producer (the one that borrows objects) thread to ensure correctness. If multiple threads require an object pool it is recommended to have {@link java.lang.ThreadLocal} instance of an object pool for each producer thread. This will ensure that only 1 thread ever increments the buffer's index.

Constraint 3 is imposed because each object in the pool must know which object pool it came from. Consumer threads will call {@link Poolable#returnToPool()} when they have finished using the object and should not be concerned with the particular object pool it came from.

Constraint 4 is imposed because the object in the pool is being recycled and consumed by other threads. Maintaining a reference to the object after returning it to the pool and subsequently making assumptions about its state will yield undefined behavior.

Constraint 5 is imposed so that {@link DisruptorObjectPool#borrowObject()} can throw an {@link java.lang.IllegalStateException} in the event that the object pool is leaking objects. If the object pool is configured to create garbage then this exception will never be thrown.