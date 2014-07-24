package com.clusin.objPool;

import com.lmax.disruptor.*;

/**
 * An object pool that utilizes the {@link com.lmax.disruptor.RingBuffer} from the LMAX Disruptor framework. Typically
 * a producer thread will borrow objects from the object pool and consumer threads will then return the borrowed
 * objects to the object pool. Object pools are most commonly implemented using a lock. A lock is required to ensure
 * mutual exclusion and visibility of changes to the underlying data structure amongst readers(producers)
 * and writers(consumers).
 *
 * The problem with this approach is that a shared lock has to be acquired and released every time an object is borrowed
 * from the object pool. The shared lock must again be acquired and released to return a borrowed object to the object
 * pool. This creates a central point of contention amongst readers and writers (the lock) and greatly reduces
 * performance. When two different threads try to acquire a lock at the same time the operating system must step in and
 * perform arbitration to decide which thread will receive the lock, and which will not. The operating system may
 * also perform its own internal housekeeping tasks while settling this dispute. This housekeeping introduces performance
 * degradations as work is being performed that is not relevant to the underlying threads' planned operation.
 *
 * Another important consideration is what to do when a producer thread outpaces the consumer threads. In a garbage
 * collected language such as Java, the implementer has 3 choices: produce no garbage, some garbage, or a lot of
 * garbage. I'm an American so I prefer to create a lot of garbage. This object pool attempts to give users the option
 * to produce no garbage or some garbage. Depending on the configuration of {@link DisruptorObjectPool#poolSize}
 * and {@link DisruptorObjectPool#createGarbage} a lot of garbage may inadvertently get created. It is
 * important to choose very carefully according to the needs of problem being solved.
 *
 * There are 5 very important constraints that must be enforced to ensure correct operation of the object pool:
 *  1. All objects in the object pool must be created via {@link DisruptorObjectPool#createNewObject(DisruptorObjectPool)}
 *  2. Only 1 thread must ever attempt to borrow objects from the object pool.
 *  3. All pooled objects must implement the {@link Poolable} interface
 *  4. Consumer threads must never maintain a reference to a pooled object after returning an object to the pool
 *  5. If the object pool is configured to create no garbage, then the pooled objects must be returned to the object
 *  pool in a timely fashion.
 *
 * Constraint 1 is imposed to pre-create objects at object pool construction time. Additionally an object pool may be
 * configured to create garbage instead of waiting for an object to be returned to the pool. This method will be called
 * in both cases.
 *
 * Constraint 2 is imposed to ensure correct behavior in a multi-threading environment. Since objects are being removed
 * and replaced from the pool the single writer principle must be enforced for the producer (the one that borrows objects)
 * thread to ensure correctness. If multiple threads require an object pool it is recommended to have
 * {@link java.lang.ThreadLocal} instance of an object pool for each producer thread. This will ensure that only 1
 * thread ever increments the buffer's index.
 *
 * Constraint 3 is imposed because each object in the pool must know which object pool it came from. Consumer threads
 * will call {@link Poolable#returnToPool()} when they have finished using the object and should not be
 * concerned with the particular object pool it came from.
 *
 * Constraint 4 is imposed because the object in the pool is being recycled and consumed by other threads. Maintaining
 * a reference to the object after returning it to the pool and subsequently making assumptions about its state will
 * yield undefined behavior.
 *
 * Constraint 5 is imposed so that {@link DisruptorObjectPool#borrowObject()} can throw an {@link java.lang.IllegalStateException}
 * in the event that the object pool is leaking objects. If the object pool is configured to create garbage then this
 * exception will never be thrown.
 *
 * @author Daniel Clusin
 */
public abstract class DisruptorObjectPool<T extends Poolable> {
    /**
     * The size of the object pool.
     */
    protected final int poolSize;

    /**
     * If this flag is set to true then the producer thread will not block waiting for objects to be returned to the
     * object pool. Likewise, consumer threads will also not spin or throw an exception when attempting to return a
     * borrowed object to the object pool. This is useful in cases where garbage is preferred to yielding execution.
     */
    protected final boolean createGarbage;

    /**
     * Number of objects created in addition to the objects in the pool. Incremented when {@link DisruptorObjectPool#createGarbage}
     * is set to true.
     */
    protected long objectsCreated = 0L;

    /**
     * The ring buffer to hold a wrapper for the objects being pooled. A wrapper around the objects being pooled is
     * used to make the ordering of objects returned to the pool irrelevant. If the order in which objects must be
     * claimed and returned is of importance then this class cannot be used.
     */
    protected final RingBuffer<ObjectWrapper> ringBuffer;

    /**
     * Sequence for the producer thread. This is used to keep track of the current element in the ring buffer
     * that has been claimed by the producer thread. If consumer threads try to surpass this sequence value they
     * will either block or drop the object being returned (create garbage).
     */
    protected final Sequence consumerSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    /**
     * Sequence barrier for the producer thread. This is used when the producing thread is borrowing objects faster than
     * the consumer threads are returning objects to the object pool. When a producer thread is unable to acquire an
     * object from the pool it will may block depending on the {@link com.lmax.disruptor.WaitStrategy}.
     */
    protected final SequenceBarrier consumerSequenceBarrier;

    /**
     * Metric to track how often a producer has to wait to acquire an object from the thread pool. Useful for
     * performance tuning.
     */
    private long borrowLockCount = 0L;

    /**
     * Create an object pool of the desired size and pre-create the objects stored in the object pool. This constructor
     * will configure the object pool to create no garbage and employ a {@link com.lmax.disruptor.BlockingWaitStrategy}
     * when no objects are available in the object pool for the producer thread.
     *
     * @param poolSize Must be greater than 1 and must be a power of 2
     * @throws IllegalArgumentException if poolSize is less than 1 or not a power of 2, or
     * {@link com.lmax.disruptor.WaitStrategy} is null and createGarbage is false
     */
    public DisruptorObjectPool(int poolSize)
    {
        this(poolSize, false);
    }

    /**
     * Create an object pool of the desire size and pre-create the objects that will be stored in the object pool. If
     * objects are unavailable for the consumer thread and createGarbage is set to false a {@link com.lmax.disruptor.BlockingWaitStrategy}
     * strategy will be employed until consumer threads return an object to the object pool.
     *
     * @param poolSize Must be greater than 1 and must be a power of 2
     * @param createGarbage If set to true the implementation will not block and create garbage instead.
     * @throws IllegalArgumentException if poolSize is less than 1 or not a power of 2, or
     * {@link com.lmax.disruptor.WaitStrategy} is null and createGarbage is false
     */
    public DisruptorObjectPool(int poolSize, boolean createGarbage)
    {
        this(poolSize, createGarbage, new BlockingWaitStrategy());
    }

    /**
     * Create an object pool of the desired size and pre-create the objects that will be stored in the object
     * pool.
     *
     * @param poolSize Must be greater than 1 and must be a power of 2
     * @param createGarbage If set to true the implementation will not block and create garbage instead.
     * @param waitingStrategy Optional waiting strategy. If preference is given to garbage this will never be invoked.
     * @throws IllegalArgumentException if poolSize is less than 1 or not a power of 2, or
     * {@link com.lmax.disruptor.WaitStrategy} is null and createGarbage is false
     */
    public DisruptorObjectPool(int poolSize, boolean createGarbage, WaitStrategy waitingStrategy)
    {
        if (!createGarbage && waitingStrategy == null)
        {
            throw new IllegalArgumentException("WaitStrategy cannot be null");
        }

        this.poolSize = poolSize;
        this.createGarbage = createGarbage;
        EventFactory<ObjectWrapper> wrapperFactory = new EventFactory<ObjectWrapper>() {
            @Override
            public ObjectWrapper newInstance() {
                return new ObjectWrapper();
            }
        };
        ringBuffer = RingBuffer.createMultiProducer(wrapperFactory, poolSize, waitingStrategy);
        ringBuffer.addGatingSequences( consumerSequence );
        consumerSequenceBarrier = ringBuffer.newBarrier();

        for (int i = 0; i < poolSize; i++)
        {
            long availCapacity = ringBuffer.remainingCapacity();
            returnObject(createNewObject(this), true);
            long remainCapacity = ringBuffer.remainingCapacity();

            //Seemed like a good idea. Probably pointless.
            if ((availCapacity - 1) != remainCapacity)
            {
                throw new IllegalStateException("Error pre-creating objects for the object pool");
            }
        }
    }

    /**
     * Acquire an object from the object pool. This call may block. See constructor.
     *
     * @return An object from the pool or null if the thread was interrupted waiting for an object.
     * @throws TimeoutException If no object was returned in the specified timeout.
     * @throws java.lang.InterruptedException if the thread was interrupted while awaiting an object.
     * @throws java.lang.IllegalStateException if consumers do not return their objects to the pool and the producer
     * encounters an empty slot in the ring buffer. Will only be thrown if the object pool is not configured to create
     * garbage.
     * See {@link DisruptorObjectPool#DisruptorObjectPool(int, boolean, com.lmax.disruptor.WaitStrategy)}
     */
    public final T borrowObject() throws TimeoutException, InterruptedException {
        long nextSequence = consumerSequence.get() + 1L;
        boolean isAvailable = ringBuffer.isPublished(nextSequence);

        if (!isAvailable)
        {
            if (createGarbage)
            {
                objectsCreated++;
                return createNewObject(this);
            }
            else
            {
                borrowLockCount++;
            }
        }

        while (true)
        {
            try
            {
                long availableSequence = consumerSequenceBarrier.waitFor( nextSequence );

                if (nextSequence <= availableSequence)
                {
                    ObjectWrapper objWrapper = ringBuffer.get(nextSequence);
                    T theObj = objWrapper.reference;
                    objWrapper.reference = null;
                    consumerSequence.set(nextSequence);

                    if (theObj == null)
                    {
                        if (!createGarbage)
                        {
                            throw new IllegalStateException("Object pool is leaking");
                        }
                        else
                        {
                            objectsCreated++;
                            theObj = createNewObject(this);
                        }
                    }

                    return theObj;
                }
            }
            catch (AlertException e)
            {
                //Returning objects don't set an alert, so this shouldn't occur.
                //In the off chance it does, wrap it in a runtime exception so it does not get
                //hidden in the event the implementation changes.
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Return an object to the object pool. The object being returned to the pool must have been created by
     * {@link DisruptorObjectPool#createNewObject(DisruptorObjectPool)}. Returning an object to this
     * pool that was not created as a result of the aforementioned method call will cause the universe to spontaneously
     * combust.
     *
     * @param anObject Pooled object.
     */
    public final void returnObject( T anObject )
    {
        returnObject(anObject, false);
    }


    private final void returnObject(T anObject, boolean startup) {
        long remainingCapacity = ringBuffer.remainingCapacity();

        if (remainingCapacity == 0 && createGarbage)
        {
            return;
        }

        long nextSequence = ringBuffer.next();
        ObjectWrapper objWrapper = ringBuffer.get(nextSequence);

        if (!startup && objWrapper.reference != null)
        {
            throw new IllegalStateException("Expected that object pools reference is null but wasn't");
        }

        objWrapper.reference = anObject;

        ringBuffer.publish(nextSequence);
    }

    /**
     * Create a new object and assign its owning pool be this instance of the object pool. The specific details of how
     * an object maintains knowledge of its owning pool is left up to the implementer. The constraint that must be
     * enforced by implementers of this method is that invoking {@link Poolable#returnToPool()} on the
     * returned object will result in calling {@link DisruptorObjectPool#returnObject(Poolable)}
     * with the instance of the pooled object being passed as an argument.
     *
     * @param owningPool
     * @return A new instance of the object being pooled
     */
    public abstract T createNewObject(DisruptorObjectPool<T> owningPool);

    /**
     * Convenience method for determining how many times a producer thread had to lock.
     *
     * @return Number of times producer waited for a consumer to return an object
     */
    public final long getBorrowLockCount()
    {
        return borrowLockCount;
    }

    /**
     * Convenience method for determining how many times a producer thread created an object.
     *
     * @return Number of objects created
     */
    public final long getObjectsCreated()
    {
        return objectsCreated;
    }

    private final class ObjectWrapper
    {
        public T reference = null;
    }
}
