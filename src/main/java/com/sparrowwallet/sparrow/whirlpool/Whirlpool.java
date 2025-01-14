package com.sparrowwallet.sparrow.whirlpool;

import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.samourai.tor.client.TorClientService;
import com.samourai.wallet.api.backend.BackendApi;
import com.samourai.wallet.api.backend.beans.UnspentOutput;
import com.samourai.wallet.hd.HD_Wallet;
import com.samourai.wallet.hd.java.HD_WalletFactoryJava;
import com.samourai.whirlpool.client.event.*;
import com.samourai.whirlpool.client.tx0.*;
import com.samourai.whirlpool.client.wallet.WhirlpoolEventService;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.WhirlpoolWalletConfig;
import com.samourai.whirlpool.client.wallet.beans.*;
import com.samourai.whirlpool.client.wallet.data.pool.PoolData;
import com.samourai.whirlpool.client.wallet.data.utxo.UtxoConfigPersisted;
import com.samourai.whirlpool.client.wallet.data.utxo.UtxoSupplier;
import com.samourai.whirlpool.client.whirlpool.ServerApi;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import com.sparrowwallet.drongo.ExtendedKey;
import com.sparrowwallet.drongo.KeyPurpose;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.drongo.Utils;
import com.sparrowwallet.drongo.protocol.ScriptType;
import com.sparrowwallet.drongo.protocol.Sha256Hash;
import com.sparrowwallet.drongo.protocol.Transaction;
import com.sparrowwallet.drongo.protocol.TransactionOutput;
import com.sparrowwallet.drongo.wallet.*;
import com.sparrowwallet.nightjar.http.JavaHttpClientService;
import com.sparrowwallet.nightjar.stomp.JavaStompClientService;
import com.sparrowwallet.nightjar.tor.WhirlpoolTorClientService;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.event.WhirlpoolMixEvent;
import com.sparrowwallet.sparrow.event.WhirlpoolMixSuccessEvent;
import com.sparrowwallet.sparrow.wallet.UtxoEntry;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class Whirlpool {
    private static final Logger log = LoggerFactory.getLogger(Whirlpool.class);

    private final HostAndPort torProxy;
    private final WhirlpoolServer whirlpoolServer;
    private final JavaHttpClientService httpClientService;
    private final JavaStompClientService stompClientService;
    private final TorClientService torClientService;
    private final SparrowWhirlpoolWalletService whirlpoolWalletService;
    private final WhirlpoolWalletConfig config;
    private HD_Wallet hdWallet;

    private BooleanProperty mixingProperty = new SimpleBooleanProperty(false);

    public Whirlpool(Network network, HostAndPort torProxy, String sCode, int maxClients, int clientDelay) {
        this.torProxy = torProxy;
        this.whirlpoolServer = WhirlpoolServer.valueOf(network.getName().toUpperCase());
        this.httpClientService = new JavaHttpClientService(torProxy);
        this.stompClientService = new JavaStompClientService(httpClientService);
        this.torClientService = new WhirlpoolTorClientService();
        this.whirlpoolWalletService = new SparrowWhirlpoolWalletService();
        this.config = computeWhirlpoolWalletConfig(sCode, maxClients, clientDelay);

        WhirlpoolEventService.getInstance().register(this);
    }

    private WhirlpoolWalletConfig computeWhirlpoolWalletConfig(String sCode, int maxClients, int clientDelay) {
        boolean onion = (torProxy != null);
        String serverUrl = whirlpoolServer.getServerUrl(onion);

        ServerApi serverApi = new ServerApi(serverUrl, httpClientService);
        BackendApi backendApi = new SparrowBackendApi();

        WhirlpoolWalletConfig whirlpoolWalletConfig = new WhirlpoolWalletConfig(httpClientService, stompClientService, torClientService, serverApi, whirlpoolServer, false, backendApi);
        whirlpoolWalletConfig.setScode(sCode);

        return whirlpoolWalletConfig;
    }

    public Collection<Pool> getPools() throws Exception {
        Tx0ParamService tx0ParamService = getTx0ParamService();
        PoolData poolData = new PoolData(config.getServerApi().fetchPools(), tx0ParamService);
        return poolData.getPools();
    }

    public Tx0Preview getTx0Preview(Pool pool, Collection<UnspentOutput> utxos) throws Exception {
        Tx0Config tx0Config = new Tx0Config();
        tx0Config.setChangeWallet(WhirlpoolAccount.BADBANK);
        Tx0FeeTarget tx0FeeTarget = Tx0FeeTarget.BLOCKS_4;
        Tx0FeeTarget mixFeeTarget = Tx0FeeTarget.BLOCKS_4;

        Tx0ParamService tx0ParamService = getTx0ParamService();

        Tx0Service tx0Service = new Tx0Service(config);
        return tx0Service.tx0Preview(utxos, tx0Config, tx0ParamService.getTx0Param(pool, tx0FeeTarget, mixFeeTarget));
    }

    public Tx0 broadcastTx0(Pool pool, Collection<BlockTransactionHashIndex> utxos) throws Exception {
        WhirlpoolWallet whirlpoolWallet = getWhirlpoolWallet();
        whirlpoolWallet.start();
        UtxoSupplier utxoSupplier = whirlpoolWallet.getUtxoSupplier();
        List<WhirlpoolUtxo> whirlpoolUtxos = utxos.stream().map(ref -> utxoSupplier.findUtxo(ref.getHashAsString(), (int)ref.getIndex())).filter(Objects::nonNull).collect(Collectors.toList());

        if(whirlpoolUtxos.size() != utxos.size()) {
            throw new IllegalStateException("Failed to find UTXOs in Whirlpool wallet");
        }

        Tx0Config tx0Config = new Tx0Config();
        tx0Config.setChangeWallet(WhirlpoolAccount.BADBANK);
        Tx0FeeTarget tx0FeeTarget = Tx0FeeTarget.BLOCKS_4;
        Tx0FeeTarget mixFeeTarget = Tx0FeeTarget.BLOCKS_4;

        return whirlpoolWallet.tx0(whirlpoolUtxos, pool, tx0Config, tx0FeeTarget, mixFeeTarget);
    }

    private Tx0ParamService getTx0ParamService() {
        try {
            SparrowMinerFeeSupplier minerFeeSupplier = new SparrowMinerFeeSupplier(config.getFeeMin(), config.getFeeMax(), config.getFeeFallback(), config.getBackendApi().fetchMinerFee());
            return new Tx0ParamService(minerFeeSupplier, config);
        } catch(Exception e) {
            log.error("Error fetching miner fees", e);
        }

        return null;
    }

    public void setHDWallet(String walletId, Wallet wallet) {
        if(wallet.isEncrypted()) {
            throw new IllegalStateException("Wallet cannot be encrypted");
        }

        try {
            Keystore keystore = wallet.getKeystores().get(0);
            ScriptType scriptType = wallet.getScriptType();
            int purpose = scriptType.getDefaultDerivation().get(0).num();
            List<String> words = keystore.getSeed().getMnemonicCode();
            String passphrase = keystore.getSeed().getPassphrase().asString();
            HD_WalletFactoryJava hdWalletFactory = HD_WalletFactoryJava.getInstance();
            byte[] seed = hdWalletFactory.computeSeedFromWords(words);
            whirlpoolWalletService.setWalletId(walletId);
            hdWallet = new HD_Wallet(purpose, words, whirlpoolServer, seed, passphrase, 1);
        } catch(Exception e) {
            throw new IllegalStateException("Could not create Whirlpool HD wallet ", e);
        }
    }

    public WhirlpoolWallet getWhirlpoolWallet() throws WhirlpoolException {
        if(whirlpoolWalletService.whirlpoolWallet() != null) {
            return whirlpoolWalletService.whirlpoolWallet();
        }

        if(hdWallet == null) {
            throw new IllegalStateException("Whirlpool HD wallet not added");
        }

        try {
            return whirlpoolWalletService.openWallet(config, Utils.hexToBytes(hdWallet.getSeedHex()), hdWallet.getPassphrase());
        } catch(Exception e) {
            throw new WhirlpoolException("Could not create whirlpool wallet ", e);
        }
    }

    public void stop() {
        if(whirlpoolWalletService.whirlpoolWallet() != null) {
            whirlpoolWalletService.whirlpoolWallet().stop();
        }
    }

    public UtxoMixData getMixData(BlockTransactionHashIndex txo) {
        if(whirlpoolWalletService.whirlpoolWallet() != null) {
            UtxoConfigPersisted config = whirlpoolWalletService.whirlpoolWallet().getUtxoConfigSupplier().getUtxoConfigPersisted(txo.getHashAsString(), (int)txo.getIndex());
            if(config != null) {
                return new UtxoMixData(config.getPoolId(), config.getMixsDone(), config.getForwarding());
            }
        }

        return null;
    }

    private void persistMixData() {
        try {
            whirlpoolWalletService.whirlpoolWallet().getUtxoConfigSupplier().persist(true);
        } catch(Exception e) {
            log.error("Error persisting mix data", e);
        }
    }

    public void mix(BlockTransactionHashIndex utxo) throws WhirlpoolException {
        if(whirlpoolWalletService.whirlpoolWallet() == null) {
            throw new WhirlpoolException("Whirlpool wallet not yet created");
        }

        try {
            WhirlpoolUtxo whirlpoolUtxo = whirlpoolWalletService.whirlpoolWallet().getUtxoSupplier().findUtxo(utxo.getHashAsString(), (int)utxo.getIndex());
            whirlpoolWalletService.whirlpoolWallet().mixNow(whirlpoolUtxo);
        } catch(Exception e) {
            throw new WhirlpoolException(e.getMessage(), e);
        }
    }

    public void mixStop(BlockTransactionHashIndex utxo) throws WhirlpoolException {
        if(whirlpoolWalletService.whirlpoolWallet() == null) {
            throw new WhirlpoolException("Whirlpool wallet not yet created");
        }

        try {
            WhirlpoolUtxo whirlpoolUtxo = whirlpoolWalletService.whirlpoolWallet().getUtxoSupplier().findUtxo(utxo.getHashAsString(), (int)utxo.getIndex());
            whirlpoolWalletService.whirlpoolWallet().mixStop(whirlpoolUtxo);
        } catch(Exception e) {
            throw new WhirlpoolException(e.getMessage(), e);
        }
    }

    public MixProgress getMixProgress(BlockTransactionHashIndex utxo) {
        if(whirlpoolWalletService.whirlpoolWallet() == null) {
            return null;
        }

        WhirlpoolUtxo whirlpoolUtxo = whirlpoolWalletService.whirlpoolWallet().getUtxoSupplier().findUtxo(utxo.getHashAsString(), (int)utxo.getIndex());
        if(whirlpoolUtxo != null && whirlpoolUtxo.getUtxoState() != null) {
            return whirlpoolUtxo.getUtxoState().getMixProgress();
        }

        return null;
    }

    public void refreshUtxos() {
        if(whirlpoolWalletService.whirlpoolWallet() != null) {
            whirlpoolWalletService.whirlpoolWallet().refreshUtxos();
        }
    }

    public HostAndPort getTorProxy() {
        return torProxy;
    }

    public boolean hasWallet() {
        return hdWallet != null;
    }

    public boolean isStarted() {
        if(whirlpoolWalletService.whirlpoolWallet() == null) {
            return false;
        }

        return whirlpoolWalletService.whirlpoolWallet().isStarted();
    }

    public void shutdown() {
        whirlpoolWalletService.closeWallet();
        httpClientService.shutdown();
    }

    private WalletUtxo getUtxo(WhirlpoolUtxo whirlpoolUtxo) {
        Wallet wallet = AppServices.get().getWallet(whirlpoolWalletService.getWalletId());
        if(wallet != null) {
            StandardAccount standardAccount = getStandardAccount(whirlpoolUtxo.getAccount());
            if(StandardAccount.WHIRLPOOL_ACCOUNTS.contains(standardAccount)) {
                wallet = wallet.getChildWallet(standardAccount);
            }

            for(BlockTransactionHashIndex utxo : wallet.getWalletUtxos().keySet()) {
                if(utxo.getHashAsString().equals(whirlpoolUtxo.getUtxo().tx_hash) && utxo.getIndex() == whirlpoolUtxo.getUtxo().tx_output_n) {
                    return new WalletUtxo(wallet, utxo);
                }
            }
        }

        return null;
    }

    public static StandardAccount getStandardAccount(WhirlpoolAccount whirlpoolAccount) {
        if(whirlpoolAccount == WhirlpoolAccount.PREMIX) {
            return StandardAccount.WHIRLPOOL_PREMIX;
        } else if(whirlpoolAccount == WhirlpoolAccount.POSTMIX) {
            return StandardAccount.WHIRLPOOL_POSTMIX;
        } else if(whirlpoolAccount == WhirlpoolAccount.BADBANK) {
            return StandardAccount.WHIRLPOOL_BADBANK;
        }

        return StandardAccount.ACCOUNT_0;
    }

    public static UnspentOutput getUnspentOutput(Wallet wallet, WalletNode node, BlockTransaction blockTransaction, int index) {
        TransactionOutput txOutput = blockTransaction.getTransaction().getOutputs().get(index);

        UnspentOutput out = new UnspentOutput();
        out.tx_hash = txOutput.getHash().toString();
        out.tx_output_n = txOutput.getIndex();
        out.value = txOutput.getValue();
        out.script = Utils.bytesToHex(txOutput.getScriptBytes());

        try {
            out.addr = txOutput.getScript().getToAddresses()[0].toString();
        } catch(Exception e) {
            //ignore
        }

        Transaction transaction = (Transaction)txOutput.getParent();
        out.tx_version = (int)transaction.getVersion();
        out.tx_locktime = transaction.getLocktime();
        if(AppServices.getCurrentBlockHeight() != null) {
            out.confirmations = blockTransaction.getConfirmations(AppServices.getCurrentBlockHeight());
        }

        if(wallet.getKeystores().size() != 1) {
            throw new IllegalStateException("Cannot mix outputs from a wallet with multiple keystores");
        }

        UnspentOutput.Xpub xpub = new UnspentOutput.Xpub();
        List<ExtendedKey.Header> headers = ExtendedKey.Header.getHeaders(Network.get());
        ExtendedKey.Header header = headers.stream().filter(head -> head.getDefaultScriptType().equals(wallet.getScriptType()) && !head.isPrivateKey()).findFirst().orElse(ExtendedKey.Header.xpub);
        xpub.m = wallet.getKeystores().get(0).getExtendedPublicKey().toString(header);
        xpub.path = node.getDerivationPath().toUpperCase();

        out.xpub = xpub;

        return out;
    }

    public String getScode() {
        return config.getScode();
    }

    public void setScode(String scode) {
        config.setScode(scode);
    }

    public boolean isMixing() {
        return mixingProperty.get();
    }

    public BooleanProperty mixingProperty() {
        return mixingProperty;
    }

    @Subscribe
    public void onMixSuccess(MixSuccessEvent e) {
        WalletUtxo walletUtxo = getUtxo(e.getWhirlpoolUtxo());
        if(walletUtxo != null) {
            log.debug("Mix success, new utxo " + e.getMixSuccess().getReceiveUtxo().getHash() + ":" + e.getMixSuccess().getReceiveUtxo().getIndex());
            persistMixData();
            Platform.runLater(() -> EventManager.get().post(new WhirlpoolMixSuccessEvent(walletUtxo.wallet, walletUtxo.utxo, e.getMixSuccess().getReceiveUtxo(), getReceiveNode(e, walletUtxo))));
        }
    }

    private WalletNode getReceiveNode(MixSuccessEvent e, WalletUtxo walletUtxo) {
        for(WalletNode walletNode : walletUtxo.wallet.getNode(KeyPurpose.RECEIVE).getChildren()) {
            if(walletUtxo.wallet.getAddress(walletNode).toString().equals(e.getMixSuccess().getReceiveAddress())) {
                return walletNode;
            }
        }

        return null;
    }

    @Subscribe
    public void onMixFail(MixFailEvent e) {
        WalletUtxo walletUtxo = getUtxo(e.getWhirlpoolUtxo());
        if(walletUtxo != null) {
            log.debug("Mix failed for utxo " + e.getWhirlpoolUtxo().getUtxo().tx_hash + ":" + e.getWhirlpoolUtxo().getUtxo().tx_output_n + " " + e.getMixFailReason());
            Platform.runLater(() -> EventManager.get().post(new WhirlpoolMixEvent(walletUtxo.wallet, walletUtxo.utxo, e.getMixFailReason())));
        }
    }

    @Subscribe
    public void onMixProgress(MixProgressEvent e) {
        WalletUtxo walletUtxo = getUtxo(e.getWhirlpoolUtxo());
        if(walletUtxo != null && isMixing()) {
            log.debug("Mix progress for utxo " + e.getWhirlpoolUtxo().getUtxo().tx_hash + ":" + e.getWhirlpoolUtxo().getUtxo().tx_output_n + " " + e.getWhirlpoolUtxo().getMixsDone() + " " + e.getMixProgress().getMixStep() + " " + e.getWhirlpoolUtxo().getUtxoState().getStatus());
            Platform.runLater(() -> EventManager.get().post(new WhirlpoolMixEvent(walletUtxo.wallet, walletUtxo.utxo, e.getMixProgress())));
        }
    }

    @Subscribe
    public void onWalletStart(WalletStartEvent e) {
        if(e.getWhirlpoolWallet() == whirlpoolWalletService.whirlpoolWallet()) {
            mixingProperty.set(true);
        }
    }

    @Subscribe
    public void onWalletStop(WalletStopEvent e) {
        if(e.getWhirlpoolWallet() == whirlpoolWalletService.whirlpoolWallet()) {
            mixingProperty.set(false);
        }
    }

    public static class PoolsService extends Service<Collection<Pool>> {
        private final Whirlpool whirlpool;

        public PoolsService(Whirlpool whirlpool) {
            this.whirlpool = whirlpool;
        }

        @Override
        protected Task<Collection<Pool>> createTask() {
            return new Task<>() {
                protected Collection<Pool> call() throws Exception {
                    return whirlpool.getPools();
                }
            };
        }
    }

    public static class Tx0PreviewService extends Service<Tx0Preview> {
        private final Whirlpool whirlpool;
        private final Wallet wallet;
        private final Pool pool;
        private final List<UtxoEntry> utxoEntries;

        public Tx0PreviewService(Whirlpool whirlpool, Wallet wallet, Pool pool, List<UtxoEntry> utxoEntries) {
            this.whirlpool = whirlpool;
            this.wallet = wallet;
            this.pool = pool;
            this.utxoEntries = utxoEntries;
        }

        @Override
        protected Task<Tx0Preview> createTask() {
            return new Task<>() {
                protected Tx0Preview call() throws Exception {
                    updateProgress(-1, 1);
                    updateMessage("Fetching premix transaction...");

                    Collection<UnspentOutput> utxos = utxoEntries.stream().map(utxoEntry -> Whirlpool.getUnspentOutput(wallet, utxoEntry.getNode(), utxoEntry.getBlockTransaction(), (int)utxoEntry.getHashIndex().getIndex())).collect(Collectors.toList());
                    return whirlpool.getTx0Preview(pool, utxos);
                }
            };
        }
    }

    public static class Tx0BroadcastService extends Service<Sha256Hash> {
        private final Whirlpool whirlpool;
        private final Pool pool;
        private final Collection<BlockTransactionHashIndex> utxos;

        public Tx0BroadcastService(Whirlpool whirlpool, Pool pool, Collection<BlockTransactionHashIndex> utxos) {
            this.whirlpool = whirlpool;
            this.pool = pool;
            this.utxos = utxos;
        }

        @Override
        protected Task<Sha256Hash> createTask() {
            return new Task<>() {
                protected Sha256Hash call() throws Exception {
                    updateProgress(-1, 1);
                    updateMessage("Broadcasting premix transaction...");

                    Tx0 tx0 = whirlpool.broadcastTx0(pool, utxos);
                    return Sha256Hash.wrap(tx0.getTxid());
                }
            };
        }
    }

    public static class StartupService extends Service<WhirlpoolWallet> {
        private final Whirlpool whirlpool;

        public StartupService(Whirlpool whirlpool) {
            this.whirlpool = whirlpool;
        }

        @Override
        protected Task<WhirlpoolWallet> createTask() {
            return new Task<>() {
                protected WhirlpoolWallet call() throws Exception {
                    updateProgress(-1, 1);
                    updateMessage("Starting Whirlpool...");

                    WhirlpoolWallet whirlpoolWallet = whirlpool.getWhirlpoolWallet();
                    if(AppServices.onlineProperty().get()) {
                        whirlpoolWallet.start();
                    }

                    return whirlpoolWallet;
                }
            };
        }
    }

    public static class ShutdownService extends Service<Boolean> {
        private final Whirlpool whirlpool;

        public ShutdownService(Whirlpool whirlpool) {
            this.whirlpool = whirlpool;
        }

        @Override
        protected Task<Boolean> createTask() {
            return new Task<>() {
                protected Boolean call() throws Exception {
                    updateProgress(-1, 1);
                    updateMessage("Disconnecting from Whirlpool...");

                    whirlpool.shutdown();
                    return true;
                }
            };
        }
    }

    public static class WalletUtxo {
        public final Wallet wallet;
        public final BlockTransactionHashIndex utxo;

        public WalletUtxo(Wallet wallet, BlockTransactionHashIndex utxo) {
            this.wallet = wallet;
            this.utxo = utxo;
        }
    }
}
