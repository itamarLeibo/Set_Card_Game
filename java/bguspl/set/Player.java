package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.UtilImpl;
import bguspl.set.WindowManager;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;


/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    private List<Integer> tokens;

    private ConcurrentLinkedQueue<Integer> conPressedKeys;

    public volatile int sleepTime = 0;

    public volatile boolean isBlocked = true;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.tokens = new LinkedList<Integer>();
        this.conPressedKeys = new ConcurrentLinkedQueue<Integer>();
    }


    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            if(sleepTime == 3){ // the player needs to be penalized
                penalty();
            } else if(sleepTime == 1){ // the player receives a point & wait
                point();
            } else{ // no need to wait
                if(!conPressedKeys.isEmpty()){
                    handleKeyPressed(conPressedKeys.remove().intValue());
                }
            }
        }
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
            while (!terminate) {
                int rndInd = ThreadLocalRandom.current().nextInt(env.config.tableSize); // extinguishing random slots between different threads
                if (table.slotToCard[rndInd] != null)
                    keyPressed(rndInd);
            }
            System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminate = true;
        try{
            if(!human){
                aiThread.join();
            }
            playerThread.join();
        }catch (InterruptedException ignored) {}
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // adding to pressedKeys queue
        if(conPressedKeys.size()<3 & !isBlocked) {
            conPressedKeys.add(slot);
        }
    }

    public void handleKeyPressed(int slot){
        if(table.slotToCard[slot] != null) {
            if (tokens.contains(slot)) { //the slot is already in the tokes list, and should be removed
                table.removeToken(id, slot);
                tokens.remove(new Integer(slot));
            } else {
                if (tokens.size() < 3) {
                    table.placeToken(id, slot);
                    tokens.add(new Integer(slot));
                    if (tokens.size() == 3) { //a set is ready to be tested
                        dealer.addSetToTest(this);
                        synchronized (dealer){ //wake up dealer
                            dealer.notifyAll();
                        }

                        try {
                            synchronized (this) { wait(); } //the player waits until the dealer checks his set
                        } catch (InterruptedException ignored) {}
                    }
                }
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        env.ui.setScore(id, ++score);

        try {
            env.ui.setFreeze(id, env.config.pointFreezeMillis);
            synchronized (this) { wait(env.config.pointFreezeMillis); }
        } catch (InterruptedException ignored) {}

        env.ui.setFreeze(id, 0);
        sleepTime = 0;
        conPressedKeys.clear();
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        if(env.config.penaltyFreezeMillis > 0){
        long penaltyFreezeTime = System.currentTimeMillis() + env.config.penaltyFreezeMillis + 500;
        while(System.currentTimeMillis() < penaltyFreezeTime){
            try {
                env.ui.setFreeze(id, penaltyFreezeTime - System.currentTimeMillis());
                synchronized (this) { wait(900); }
            } catch (InterruptedException ignored) {}
        }
        env.ui.setFreeze(id, 0); // back to black
        }
        sleepTime = 0;
        conPressedKeys.clear();
    }

    public int getScore() {
        return score;
    }

    public List<Integer> getTokens(){
        return tokens;
    }

    public void emptyTokenList(){
        tokens.clear();
    }

    public boolean removeToken (int slot){
        return tokens.remove(new Integer(slot));
    }

    public void removeFromPressedKeys(int slot){
        while(conPressedKeys.remove(new Integer(slot))){}
    }

    public void clearPressedKeys(){
        conPressedKeys.clear();
    }
    public Thread getPlayerThread(){
        return playerThread;
    }

    public ConcurrentLinkedQueue<Integer> getConPressedKeys(){
        return conPressedKeys;
    }

}
