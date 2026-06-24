# Guida RLbT: Q-Learning tabellare vs Deep Q-Learning (DQN)

Questa guida spiega come RLbT scelga tra l'agente Q-learning tabellare e l'agente
Deep Q-Network (DQN), quali file di configurazione usare e come lanciare i test/training.

## 1. Cosa è stato fatto

E' stato aggiunto un secondo algoritmo di reinforcement learning accanto al
Q-learning tabellare già esistente (`QLearningRL`):

- **`DeepQLearningRL`** (`src/main/java/eu/fbk/iv4xr/rlbt/DeepQLearningRL.java`):
  implementazione DQN che sostituisce la Q-table con una rete neurale (DeepLearning4J,
  2 hidden layer ReLU + output layer lineare). Include le due tecniche di stabilizzazione
  standard del DQN:
  - **Experience replay**: le transizioni `(s, a, r, s', done)` vengono salvate in un
    buffer circolare e il training avviene su mini-batch campionati casualmente da esso,
    invece che sulla singola transizione appena osservata.
  - **Target network**: una copia "congelata" della rete principale usata per calcolare
    il target di Bellman, risincronizzata ogni 100 step. Si usa inoltre la variante
    *Double DQN* (azione scelta dalla rete principale, valutata dalla rete target) per
    ridurre la sovrastima dei Q-value.
- **`BurlapConfiguration`** (`src/main/java/eu/fbk/iv4xr/rlbt/configuration/BurlapConfiguration.java`):
  aggiunti i parametri specifici del DQN (`burlap.qlearning.dqn_lr`,
  `burlap.qlearning.epsilonmin`, `burlap.network.hidden_size`).
- **`LabRecruitsRLEnvironment.loadEntityIds(...)`**: nuovo metodo statico che legge gli
  ID delle entità (bottoni/porte) dal CSV del livello *prima* di avviare l'ambiente.
  Serve perché la rete DQN, a differenza della Q-table, ha bisogno di un vettore di
  input/output a dimensione fissa, nota già al momento della costruzione della rete.
- **`RlbtMain`** e **`RlbtMultiAgentMain`**: aggiunti i metodi
  `executeDeepQLearningTrainingOnLabRecruits()` / `executeDeepQLearningTestingOnLabRecruits()`
  (e l'equivalente multi-agent), selezionati a runtime in base al parametro
  `burlap.algorithm` letto dal file di configurazione BURLAP.

## 2. Come il programma scegli tra DQN e Q-table

La scelta **non** è hard-coded: dipende interamente dal parametro

```
burlap.algorithm=QLearning        # oppure
burlap.algorithm=DeepQLearning
```

presente nel file di configurazione BURLAP passato con `-burlapConfig`.

In `RlbtMain.executeTraining(...)` (e gli equivalenti `executeTesting`, `executeRandom`,
`postAnalysisLearningTraces`, e le stesse logiche in `RlbtMultiAgentMain`):

```java
String alg = (String) burlapConfiguration.getParameterValue("burlap.algorithm");
if (alg.equalsIgnoreCase(BurlapAlgorithm.QLearning.toString())) {
    // -> QLearningRL: usa una HashMap (Q-table) indicizzata sullo stato hashato
} else if (alg.equalsIgnoreCase(BurlapAlgorithm.DeepQLearning.toString())) {
    // -> DeepQLearningRL: usa la rete neurale al posto della Q-table
} else {
    throw new RuntimeException("Algorithm " + alg + " not supported");
}
```

Quindi:
- Il file SUT (`lrLevelSingleAgent.config` o `lrLevelMultiAgent.config`) descrive **solo**
  il livello, gli agenti e il tipo di reward: non contiene nulla relativo all'algoritmo,
  quindi funziona automaticamente sia con Q-learning che con DQN.
- Il file BURLAP (`burlap_test.config` o `burlap_dqn_test.config`) è quello che decide
  l'algoritmo, tramite `burlap.algorithm`.

Per cambiare algoritmo basta quindi cambiare il file passato a `-burlapConfig`, **senza
toccare** il file SUT.

## 3. File di configurazione disponibili

Dopo la pulizia, in `src/test/resources/configurations/` restano solo 4 file:

| File | Tipo | Uso |
|---|---|---|
| `burlap_test.config` | BURLAP | `burlap.algorithm=QLearning` — agente tabellare |
| `burlap_dqn_test.config` | BURLAP | `burlap.algorithm=DeepQLearning` — agente DQN |
| `lrLevelSingleAgent.config` | SUT | livello/agente singolo, compatibile con entrambi gli algoritmi |
| `lrLevelMultiAgent.config` | SUT | livello/agenti multipli, compatibile con entrambi gli algoritmi |

Sono stati rimossi `buttons_doors_1.config` (duplicato di `lrLevelSingleAgent.config`,
mancavano solo alcuni parametri secondari) e `lrLevelMultiAgent_medium4agents.config`
(variante non referenziata da nessun codice, duplicato di `lrLevelMultiAgent.config`
su un livello diverso). I default in `RlbtMain.java` e `RlbtMultiAgentMain.java` sono
stati aggiornati per puntare a `lrLevelSingleAgent.config`/`lrLevelMultiAgent.config`.

## 4. Comandi per lanciare training/testing

Tutti i comandi vanno eseguiti dalla root del progetto (dove si trova questo file),
dopo aver compilato il progetto (`mvn package`) o usando direttamente le classi compilate
da IDE/Maven (`mvn exec:java -Dexec.mainClass=...`).

### 4.1 Training, agente singolo, Q-table

```
java -cp target/classes:... eu.fbk.iv4xr.rlbt.RlbtMain \
  -trainingMode \
  -burlapConfig src/test/resources/configurations/burlap_test.config \
  -sutConfig src/test/resources/configurations/lrLevelSingleAgent.config
```

### 4.2 Training, agente singolo, DQN

Stesso comando, basta cambiare solo `-burlapConfig`:

```
java -cp target/classes:... eu.fbk.iv4xr.rlbt.RlbtMain \
  -trainingMode \
  -burlapConfig src/test/resources/configurations/burlap_dqn_test.config \
  -sutConfig src/test/resources/configurations/lrLevelSingleAgent.config
```

### 4.3 Testing (carica il modello/Q-table salvato in training)

```
java -cp target/classes:... eu.fbk.iv4xr.rlbt.RlbtMain \
  -testingMode \
  -burlapConfig src/test/resources/configurations/burlap_dqn_test.config \
  -sutConfig src/test/resources/configurations/lrLevelSingleAgent.config
```

`burlap.algorithm` nel file BURLAP deve essere lo stesso usato in training, altrimenti
il programma tenta di deserializzare il file sbagliato (`qtable.ser` vs `qnetwork.ser`).

### 4.4 Multi-agente (training)

```
java -cp target/classes:... eu.fbk.iv4xr.rlbt.RlbtMultiAgentMain \
  -multiagentTrainingMode \
  -burlapConfig src/test/resources/configurations/burlap_test.config \
  -sutConfig src/test/resources/configurations/lrLevelMultiAgent.config
```

Per usare DQN in multi-agente basta, come sopra, passare `burlap_dqn_test.config`.

### 4.5 Esplorazione casuale (baseline)

```
java -cp target/classes:... eu.fbk.iv4xr.rlbt.RlbtMain \
  -randomMode \
  -burlapConfig src/test/resources/configurations/burlap_test.config \
  -sutConfig src/test/resources/configurations/lrLevelSingleAgent.config
```

## 5. Come funzionano internamente training e testing

1. **Training**: per ogni episodio (`burlap.num_of_episodes`), l'agente interagisce con
   l'ambiente LabRecruits per al massimo `labrecruits.max_actions_per_episode` azioni.
   - Q-table: ad ogni step aggiorna direttamente il valore `Q(s,a)` nella HashMap con la
     regola di Bellman (parametri `burlap.qlearning.lr`/`gamma`).
   - DQN: ad ogni step salva la transizione nel replay buffer e allena la rete su un
     mini-batch; ogni 100 step sincronizza la target network.
   - Epsilon (`burlap.qlearning.epsilonval`) decresce ad ogni episodio fino al minimo
     (`burlap.qlearning.epsilonmin` per il DQN; per la Q-table il floor è implicito).
   - A fine training viene salvato il modello (`qtable.ser` o `qnetwork.ser`) e un
     riepilogo (`episodeSummary.txt`) nella cartella `rlbt-files/results/...`.
2. **Testing**: l'agente viene ricreato, il modello salvato viene deserializzato e si
   eseguono episodi con policy greedy (epsilon=0, nessun apprendimento), per valutare
   la coverage raggiunta.

## 6. Test automatici esistenti

I test JUnit in `src/test/java/.../agents/` (`FireHazardIndirectTest`,
`SimpleMultiAgentTest1`) **non** eseguono training RL: sono test funzionali del motore
iv4xr/LabRecruits stesso (verificano che un agente di test riesca a raggiungere certi
goal nel gioco). Si lanciano con Maven/JUnit standard:

```
mvn test
```

oppure da IDE, eseguendo la singola classe. Per provare effettivamente il training/testing
RL (Q-learning o DQN) bisogna usare i comandi da riga di comando della sezione 4, non
`mvn test`.
