package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * sets to be tested
     */
    private volatile Queue<Player> waitingSetsForTest;

    private Thread dealerThread;

    private Semaphore s;


    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        waitingSetsForTest = new LinkedList<Player>();
        s = new Semaphore(1,true);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        System.out.printf("Info: Thread %s starting.%n", Thread.currentThread().getName());
        dealerThread = Thread.currentThread();
        placeCardsOnTable();
        for (Player p : players){
            Thread playerThread = new Thread(p,"player" + p.id);
            playerThread.start();
            try {
                synchronized (this) { wait(10); }
            } catch (InterruptedException ignored) {}
        }
        changeBlockedToAllPlayers(false);
        while (!shouldFinish()) {
            placeCardsOnTable();
            resetReshuffleTime();
            timerLoop();
            updateTimerDisplay(reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis & reshuffleTime - System.currentTimeMillis()>0);
            preparingNewRound();
        }
        terminate();
        for(Player p : players){
            try{ p.getPlayerThread().join();} catch(InterruptedException ignored){};
        }
        announceWinners();
        System.out.printf("Info: Thread %s terminated.%n", Thread.currentThread().getName());
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            updateTimerDisplay(reshuffleTime - System.currentTimeMillis() < env.config.turnTimeoutWarningMillis & reshuffleTime - System.currentTimeMillis()>0);
            if(!waitingSetsForTest.isEmpty()) {
                handleSet(waitingSetsForTest.remove().id);
                try {
                    synchronized (this) { wait(10); }
                } catch (InterruptedException ignored) {}
            }
            else{ // sleep if there is no set to test
                sleepUntilWokenOrTimeout();
            }
        }
    }

    public void resetReshuffleTime(){
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 500;
    }

    /**
     * Called when the game should be terminated due to an external event.
     */

    public void terminate() {
        changeBlockedToAllPlayers(true);
        for(int i=players.length-1; i>=0; i--){
            players[i].terminate();
        }
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks if any cards should be removed from the table and returns them to the deck.
     */
    private void removeCardsFromTable(int [] cards) {
        // TODO implement
        for(int card : cards) {
            if(table.cardToSlot[card] != null){
                int slot = table.cardToSlot[card].intValue();
                table.removeCard(slot);
                for(Player p : players) {
                    if (p.removeToken(slot)) { // the player has a token on this slot
                        waitingSetsForTest.remove(p); // delete from dealer's queue - if the set is irrelevant
                        synchronized (players[p.id]){players[p.id].notifyAll();}
                    }
                    p.removeFromPressedKeys(slot);// delete all key pressed players - pressed keys queue
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    public void placeCardsOnTable() {
        Collections.shuffle(deck);
        for(int i=0; i < table.slotToCard.length; i++){
            if(table.slotToCard[i] == null){
                if (!deck.isEmpty()) {
                    int rndCard = deck.get(0).intValue();
                    table.placeCard(rndCard, i);
                    env.ui.placeCard(rndCard, i);
                    deck.remove(0);
                }
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */

    private void sleepUntilWokenOrTimeout() {
        int sleepTime = 800;
        if(reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis){
            sleepTime = 10;
        }
        try {
            synchronized (this) {
                wait(sleepTime);
            }
        } catch (InterruptedException ignored) {}
    }


    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reset);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    public void removeAllCardsFromTable() {
        for(int i=0; i < table.slotToCard.length; i++){
            if(table.slotToCard[i] != null){
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }
    }

    /**
     * preparing for a new round
     */
    private void emptyAllPlayersQueues(){
        for(Player p : players){
            p.emptyTokenList();
            p.clearPressedKeys();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // TODO implement
        int maxscore = 0;
        List<Integer> currWinners = new LinkedList<Integer>();
        for (Player p : players){
            if(p.getScore()>maxscore) {
                currWinners.clear();
                maxscore = p.getScore();
                currWinners.add(p.id);
            }
            else if (p.getScore()==maxscore) {
                currWinners.add(p.id);
            }
        }
        int [] winners = new int[currWinners.size()];
        int j=0;
        for(Integer i : currWinners){
            winners[j] = i.intValue();
            j++;
        }
        env.ui.announceWinner(winners);
    }

    public void handleSet(int playerId){
        int [] cardsSet = new int [3];
        int i = 0;
        for(Integer slot: players[playerId].getTokens()){
            cardsSet[i] = table.slotToCard[slot.intValue()].intValue();
            i++;
        }
        if(env.util.testSet(cardsSet)){ // the set is legal
            System.out.println("Info: player"+ playerId + " has found a set!");
            players[playerId].sleepTime = 1;
            removeCardsFromTable(cardsSet);
            placeCardsOnTable();
            resetReshuffleTime(); // reset the timer
        }
        else{ // the set is illegal
            players[playerId].sleepTime = 3;
        }

        try {
            synchronized (this) { wait(10); }
        } catch (InterruptedException ignored) {}

        synchronized (players[playerId]){ //need to wake up the player that his set has been checked
            players[playerId].notifyAll();
        }
    }


    /**
     * Players adding themselves to testing queue
     */
    public void addSetToTest(Player p){
        try {
            s.acquire(); // making sure that only one player can add himself at a time, and the order is fair
        }catch (InterruptedException ignored) {}

        waitingSetsForTest.add(p);

        s.release();
    }

    public Player[] getPlayers(){
        return players;
    }

    public List<Integer> getDeck(){
        return deck;
    }

    /**
     * changing block state to all the players
     */
    private void changeBlockedToAllPlayers(boolean isBlocked){
        for(Player p : players){ // wake up all players
            p.isBlocked = isBlocked;
        }
    }

    /**
     * called each time a round iteration is finished
     */
    private void preparingNewRound(){
        changeBlockedToAllPlayers(true);
        removeAllCardsFromTable();
        emptyAllPlayersQueues();
        waitingSetsForTest.clear();
        for(Player p : players){ // wake up all players
            synchronized (players[p.id]){players[p.id].notifyAll();}
        }
        changeBlockedToAllPlayers(false);
    }
}


