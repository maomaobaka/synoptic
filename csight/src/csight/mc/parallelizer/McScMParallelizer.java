package csight.mc.parallelizer;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

import csight.mc.mcscm.McScM;
import csight.mc.parallelizer.ParallelizerTask.ParallelizerCommands;
import csight.model.fifosys.cfsm.CFSM;

/**
 * <p>
 * The McScMParallelizer manages concurrent model checking and operates
 * concurrently with CSightMain in its own thread. N model checking processes
 * are run concurrently as commanded by CSightMain, and results are given back
 * to CSightMain when model checking completes. McScMParallelizer will always
 * wait for tasks from CSightMain before starting new McScM model checking
 * processes. CSightMain is allowed to send tasks in any order, provided the
 * tasks do not cause numRunning (number of actively running McScM instances) to
 * exceed numParallel (the max parallelization factor). McScMParallelizer will
 * always run startOne() and stopAll() non-concurrently.
 * </p>
 * <p>
 * McScMParallelizer communicates with CSightMain through two queues:
 * </p>
 * <p>
 * QUEUE1 (taskChannel): A BlockingQueue with capacity one that CSightMain uses
 * to send tasks to the McScMParallelizer. ParallelizerTask contains
 * ParallelizerCommands, the corresponding inputs (@see ParallelizerInput), and
 * the refinement counter. ParallelizerCommands is an enumeration of the
 * following: <br />
 * 1) START_K: Starts K model checking processes, where K = min{numParallel,
 * invsToCheck.size()}. Corresponding inputs sent as part of the task is
 * expected to be of same size as K.<br/>
 * 2) START_ONE: Starts one model checking process. Corresponding inputs sent in
 * the task is expected to be of size 1.<br />
 * 3) STOP_ALL: Stop all model checking processes and their results will be
 * discarded. Corresponding inputs sent in the task is expected to be null.<br />
 * The refinement counter will act as a guard to prevent out-dated model
 * checking runs from executing. STOP_ALL is expected to be associated with the
 * new refinementCounter.
 * </p>
 * <p>
 * QUEUE2 (resultsChannel): An unbounded BlockingQueue that provides completed
 * model checking results back to CSightMain from McScMParallelizer. Each
 * ParallelizerResult contains the invariant for the model checking run, the
 * MCResult class, and the refinement counter to prevent CSightMain from using
 * out-dated results. ParallelizerResult can also pass exceptions to CSightMain
 * using this queue.
 */
public class McScMParallelizer implements Runnable {

    /**
     * Volatile to provide concurrent access as each model checking process will
     * access numRunning and decrement it after model checking completes.
     * Represents the total number of processes running with the current
     * refinementCount.
     */
    private volatile int numRunning;

    /**
     * The current refinement count as provided by CSightMain via task channel.
     * Volatile to provide concurrent access as each model checking process will
     * access refinementCount and make sure no out-dated results are returned
     * and numRunning is not incorrectly decremented when out-dated processes
     * finish executing.
     */
    private volatile int refinementCount;

    /**
     * Lock when results from being written to resultChannel. Race condition can
     * occur with stopAll(), and writeResult() such that numRunning will be
     * decremented and results will be written after stopAll() has set
     * numRunning to 0, causing numRunning to become negative. Race condition
     * can occur with startOne(), and writeResult() such that CSightMain may
     * receive the result and startOne() may begin execution before
     * writeResult() have decremented numRunning, causing numRunning to exceed
     * numParallel. stopAll() will obtain a writeLock to block writeResult()
     * from executing concurrently while processes are being stopped.
     * writeResult() will obtain a readLock to not block writeResult() from
     * executing concurrently, but block stopAll() from executing concurrently.
     */
    private final ReentrantReadWriteLock resultsLock;

    /** The maximum number of processes to run at once. */
    private final int numParallel;

    private final BlockingQueue<ParallelizerTask> taskChannel;
    private final BlockingQueue<ParallelizerResult> resultsChannel;

    /** The location of McScM. */
    protected final String mcPath;
    protected final Logger logger;

    /**
     * Executes the model checking processes concurrently in a fixed thread pool
     * size. ExecutorService will handle starting and terminating of threads for
     * us.
     */
    private ExecutorService eService;

    /**
     * Creates a new Parallelizer to run in a thread.
     * 
     * @param numParallel
     * @param mcPath
     * @param taskChannel
     * @param resultsChannel
     */
    public McScMParallelizer(int numParallel, String mcPath,
            BlockingQueue<ParallelizerTask> taskChannel,
            BlockingQueue<ParallelizerResult> resultsChannel) {
        this.numParallel = numParallel;
        this.mcPath = mcPath;
        this.taskChannel = taskChannel;
        this.resultsChannel = resultsChannel;

        numRunning = 0;
        resultsLock = new ReentrantReadWriteLock();

        logger = Logger.getLogger("McScM Parallelizer");
        eService = Executors.newFixedThreadPool(numParallel);
    }

    @Override
    public void run() {
        logger.info("McScM Parallelizer has started.");
        try {
            while (true) {
                // This blocks until there is an item available to take.
                ParallelizerTask task = taskChannel.take();
                if (task.cmd == ParallelizerCommands.START_K) {
                    assert (task.refinementCounter == refinementCount);
                    assert (task.inputs.size() <= numParallel);
                    startK(task.inputs, task.refinementCounter);

                } else if (task.cmd == ParallelizerCommands.START_ONE) {
                    assert (task.refinementCounter == refinementCount);

                    startOne(task.inputs.get(0), task.refinementCounter);

                } else if (task.cmd == ParallelizerCommands.STOP_ALL) {
                    assert (task.refinementCounter > refinementCount);

                    stopAll(task.refinementCounter);
                }
            }
        } catch (InterruptedException e) {
            boolean success;
            do {
                // Result may occasionally fail to enqueue into the results
                // channel, so attempts are made until the result is
                // successfully written into the queue. If a limit of how
                // many attempts are introduced, we need to introduce a
                // timeout in CSightMain.waitForResult() as there may be no
                // result returned through the queue.
                success = writeResult(ParallelizerResult.exceptionResult(e));
            } while (!success);

        }
    }

    /**
     * Starts one model checking process. This method will always run
     * non-concurrently with itself, and stopAll()
     * 
     * @param input
     * @param refinementCounter
     * @throws InterruptedException
     * @return True iff a STOP_ALL command is sent
     */
    private boolean startOne(final ParallelizerInput input,
            final int refinementCounter) throws InterruptedException {
        // An optimization to stop starting new processes when STOP_ALL is sent
        // while starting N processes.
        if (checkIfStopAll()) {
            return true;
        }

        // Get the CFSM corresponding to the partition graph.
        final CFSM cfsm = input.cfsm;
        final InvariantTimeoutPair invTimeoutPair = input.invTimeoutPair;

        Runnable runnable = new Runnable() {

            @Override
            public void run() {
                ParallelizerResult result;

                try {
                    cfsm.augmentWithInvTracing(invTimeoutPair.getInv());

                    String mcInputStr = cfsm.toScmString("checking_scm_"
                            + invTimeoutPair.getInv().getConnectorString());

                    logger.fine("*******************************************************");
                    logger.fine("Checking ... "
                            + invTimeoutPair.getInv().toString()
                            + ", refinements so far: " + refinementCounter
                            + ". Timeout = " + invTimeoutPair.getTimeout()
                            + ".");
                    logger.fine("*******************************************************");

                    McScM mcscm = new McScM(mcPath);

                    mcscm.verify(mcInputStr, invTimeoutPair.getTimeout());
                    result = ParallelizerResult.verificationResult(
                            invTimeoutPair,
                            mcscm.getVerifyResult(cfsm.getChannelIds()),
                            refinementCounter);

                } catch (TimeoutException e) {
                    // Model checking timed out.
                    result = ParallelizerResult.timeOutResult(invTimeoutPair,
                            refinementCounter);

                } catch (InterruptedException e) {
                    // Model checking process was interrupted.
                    result = ParallelizerResult.interruptedResult(
                            invTimeoutPair, refinementCounter);
                } catch (Exception e) {
                    // Exception during model checking. Send it to CSightMain.
                    result = ParallelizerResult.exceptionResult(e,
                            refinementCounter);
                }

                boolean success;
                do {
                    // Result may occasionally fail to enqueue into the results
                    // channel, so attempts are made until the result is
                    // successfully written into the queue. If a limit of how
                    // many attempts are introduced, we need to introduce a
                    // timeout in CSightMain.waitForResult() as there may be no
                    // result returned through the queue.
                    success = McScMParallelizer.this.writeResult(result);
                } while (!success);
            }

        };

        try {
            // Gets a write lock to block writeResult(). @see resultsLock
            resultsLock.writeLock().lockInterruptibly();
            eService.submit(runnable);
            numRunning++;

            assert (numRunning >= 0);
            assert (numRunning <= numParallel);

            return false;
        } finally {
            resultsLock.writeLock().unlock();
        }
    }

    /**
     * Stops all model checking processes and prepares for new tasks. This
     * method will always run non-concurrently with itself, and startOne()
     * 
     * @param refinementCounter
     * @throws InterruptedException
     */
    private void stopAll(int refinementCounter) throws InterruptedException {
        logger.info("Stopping all model checking processes...");
        eService.shutdownNow();

        try {
            // Gets a write lock to block writeResult(). @see resultsLock
            resultsLock.writeLock().lockInterruptibly();
            refinementCount = refinementCounter;
            eService = Executors.newFixedThreadPool(numParallel);
            numRunning = 0;
        } finally {
            resultsLock.writeLock().unlock();
        }
    }

    /**
     * Starts K model checking processes based on the inputs
     * 
     * @param inputs
     * @param refinementCounter
     * @throws InterruptedException
     */
    private void startK(List<ParallelizerInput> inputs, int refinementCounter)
            throws InterruptedException {
        assert (numRunning == 0);

        for (ParallelizerInput input : inputs) {
            if (startOne(input, refinementCounter)) {
                return;
            }
        }
    }

    /**
     * Returns true if a STOP_ALL command has been sent by CSightMain. This is
     * an optimization to stop results and starting processes as soon as
     * STOP_ALL command is sent.
     * 
     * @return
     */
    private boolean checkIfStopAll() {
        ParallelizerTask command = taskChannel.peek();
        if (command != null) {
            if (command.cmd == ParallelizerCommands.STOP_ALL) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes a ParallelizerResult to the results channel if the result is not
     * out-dated, and decrement numRunning to mark termination of a model
     * checking process.
     * 
     * @param result
     * @return False iff exception occurred when writing to results Channel,
     *         else true
     */
    protected boolean writeResult(ParallelizerResult result) {
        try {
            // Gets a write lock to block startOne() and stopAll(). @see
            // resultsLock
            resultsLock.readLock().lockInterruptibly();
            /**
             * Java's ExecutorService.shutDownNow() does not guarantee
             * termination of threads, causing race condition. No results should
             * be written to resultsChannel if model checking is stopped.
             * checkIfStopAll() is an optimization. RefinementCounter check is
             * necessary to prevent out-dated processes from returning a result
             * and decrementing numRunning, as this would incorrectly decrease
             * numRunning to below the number of processes of the correct
             * refinement counter that are currently being ran.
             */
            if (checkIfStopAll()
                    || refinementCount != result.getRefinementCounter()) {
                // Model checking for this refinement already stopped. Don't
                // return any more results and don't decrement numRunning.
                return true;
            }

            logger.fine("Parallelizer returned a result. Refinement: "
                    + result.getRefinementCounter());
            resultsChannel.put(result);
            numRunning--;

            assert (numRunning >= 0);

            return true;
        } catch (InterruptedException e) {
            // BlockingQueue.put() may throw InterruptedException occasionally.
            logger.info("Parallelizer failed to enqueue result.");

            return false;
        } finally {
            resultsLock.readLock().unlock();
        }
    }
}
