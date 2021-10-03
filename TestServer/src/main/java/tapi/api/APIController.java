package tapi.api;

import com.google.gson.Gson;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.view.RedirectView;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.*;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.http.HttpService;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;
import org.web3j.utils.Bytes;
import org.web3j.utils.Numeric;
import tapi.api.crypto.CoSignedIdentifierAttestation;
import tapi.api.crypto.SignedIdentifierAttestation;
import tapi.api.crypto.core.SignatureUtility;
import twitter4j.Twitter;
import twitter4j.TwitterFactory;
import twitter4j.auth.AccessToken;
import twitter4j.auth.RequestToken;
import twitter4j.conf.Configuration;
import twitter4j.conf.ConfigurationBuilder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.sleep;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction;
import static org.web3j.tx.Contract.staticExtractEventParameters;
import static tapi.api.CryptoFunctions.sigFromByteArray;

@Controller
@RequestMapping("/")
public class APIController
{
    private static final String CONTRACT = "0xE6aAf7C1bBD92B6FFa76ADF47816572EC9f5Ba76";
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    public static final String baseFilePath = "../../files/";
    private final Map<String, BigInteger> hashToBlockNumber = new ConcurrentHashMap<>();

    private static final long CHAIN_ID = 4; //Rinkeby
    private static final String CHAIN_NAME = "Rinkeby";
    private static final BigDecimal GWEI_FACTOR = BigDecimal.valueOf(1000000000L);
    private static final BigDecimal WEI_FACTOR = BigDecimal.valueOf(1000000000000000000L);

    private static final BigInteger GAS_LIMIT_CONTRACT = new BigInteger("432000"); //

    private static BigDecimal currentGasPrice = BigDecimal.ZERO;

    private final String CONTRACT_KEY;
    private final String INFURA_KEY;
    private final String ATTESTATION_KEY;
    private final String TWITTER_API_KEY;
    private final String TWITTER_KEY_SECRET;
    private final String TWITTER_BEARER_TOKEN;
    private final String deploymentAddress;

    public final static String TWITTER_URL = "https://twitter.com/";

    private final Map<String, CoSignedIdentifierAttestation> attestationMap = new ConcurrentHashMap<>();
    private final Map<String, Map<BigInteger, Tip>> tipUserMap = new ConcurrentHashMap<>();
    private final Map<String, TwitterData> twitterIdMap = new ConcurrentHashMap<>();

    @Nullable
    private Disposable gasFetchDisposable;

    @Autowired
    public APIController()
    {
        String keys = load("../../keys.secret");
        String[] sep = keys.split(",");
        INFURA_KEY = sep[0];
        CONTRACT_KEY = sep[1];
        ATTESTATION_KEY = sep[3];
        TWITTER_API_KEY = sep[4];
        TWITTER_KEY_SECRET = sep[5];
        TWITTER_BEARER_TOKEN = sep[6];
        if (sep.length > 7 && !sep[7].equals("END_DATA"))
        {
            deploymentAddress = sep[2];
        }
        else
        {
            deploymentAddress = "http://192.168.50.9:8081/";
        }

        AttestationHandler.setupKeys(ATTESTATION_KEY);
        //start gas price cycle
        gasFetchDisposable = Observable.interval(0, 30, TimeUnit.SECONDS)
                .doOnNext(l -> getGasPriceGWEI()).subscribe();
    }

    public Single<String> createTransaction(ECKeyPair key, String toAddress, BigInteger value,
                                            BigInteger gasPrice, BigInteger gasLimit, byte[] data, long chainId)
    {
        final Web3j web3j = getWeb3j();

        return getLastTransactionNonce(web3j, "0x" + Keys.getAddress(key.getPublicKey()))
                .flatMap(nonce -> signTransaction(key, toAddress, value, gasPrice, gasLimit, nonce.longValue(), data, chainId))
                .map(signedTransactionBytes -> {
                    EthSendTransaction raw = web3j
                            .ethSendRawTransaction(Numeric.toHexString(signedTransactionBytes))
                            .send();

                    if (raw.hasError())
                    {
                        throw new Exception(raw.getError().getMessage());
                    }
                    return raw.getTransactionHash();
                });
    }

    public Single<BigInteger> getLastTransactionNonce(Web3j web3j, String walletAddress)
    {
        return Single.fromCallable(() -> {
            try
            {
                EthGetTransactionCount ethGetTransactionCount = web3j
                        .ethGetTransactionCount(walletAddress, DefaultBlockParameterName.PENDING)
                        .send();
                return ethGetTransactionCount.getTransactionCount();
            }
            catch (Exception e)
            {
                return BigInteger.ZERO;
            }
        });
    }

    private BigDecimal getGasPriceGWEI()
    {
        BigInteger gasPrice = BigInteger.valueOf(2000000000L);
        try
        {
            gasPrice = getWeb3j().ethGasPrice().send().getGasPrice();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        currentGasPrice = new BigDecimal(gasPrice).divide(GWEI_FACTOR, RoundingMode.HALF_DOWN)
                .setScale(4, RoundingMode.HALF_DOWN);

        return currentGasPrice;
    }

    private BigDecimal parseBigDecimal(String s)
    {
        BigDecimal val = BigDecimal.ZERO;

        try {
            val = new BigDecimal(s);
        } catch (Exception e) {
            //
        }

        return val;
    }

    public static String getAddress(ECKeyPair keyPair)
    {
        BigInteger pubKeyBI = keyPair.getPublicKey();
        //now get the address
        String addr = Keys.getAddress(pubKeyBI);
        return addr;
    }

    private OkHttpClient buildClient()
    {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    private Web3j getWeb3j()
    {
        //Infura
        String chain = "";
        switch ((int)CHAIN_ID) {
            default:
            case 1:
                break;
            case 4:
                chain = "rinkeby.";
                break;
            case 42:
                chain = "kovan.";
                break;
        }

        HttpService nodeService = new HttpService("https://" + chain + "infura.io/v3/" + INFURA_KEY,  buildClient(), false);
        return Web3j.build(nodeService);
    }

    private static Function checkEncoding(byte[] encoding) {
        return new Function(
                "getCurrentNonce",
                Collections.singletonList(new DynamicBytes(encoding)),
                Collections.singletonList(new TypeReference<Bool>() {}));
    }

    private static Function getCurrentNonce() {
        return new Function("getCurrentNonce",
                Arrays.<Type>asList(),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
    }

    private static Function getCurrentAllowance(String tokenOwner, String spender)
    {
        return new Function("allowance",
                Arrays.asList(new Address(tokenOwner), new Address(spender)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
    }

    private static Function approve(String adminAddress, BigInteger newAllowance)
    {
        return new Function("approve",
                Arrays.asList(new Address(adminAddress), new Uint256(newAllowance)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
    }

    private static Function approveNFT(String wrapperAddr, BigInteger tokenId)
    {
        return new Function("approve",
                Arrays.asList(new Address(wrapperAddr), new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
    }

    private static Function tokenURI(BigInteger tokenId)
    {
        return new Function("tokenURI",
                Arrays.asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
    }

    private static Function commitNFT(List<ERC721Token> tokens, PaymentToken paymentToken, String identifier)
    {
        return new Function("commitNFT",
                Arrays.asList(getERC721Array(tokens),
                        getPaymentTokenArray(paymentToken),
                        new Utf8String(identifier)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
    }

    private static Function commitNFT(List<ERC721Token> tokens, String identifier)
    {
        return new Function("commitNFT",
                Arrays.asList(getERC721Array(tokens),
                        new Utf8String(identifier)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
    }

    private static Function collectTip(List<BigInteger> tipIds, CoSignedIdentifierAttestation wrappedAttestation)
    {
        return new Function("collectTip",
                Arrays.asList(getTipIds(tipIds), new DynamicBytes(wrappedAttestation.getDerEncoding())),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
    }

    protected static org.web3j.abi.datatypes.DynamicArray<?> getTipIds(List<BigInteger> tipIds)
    {
        List<Uint256> tipIdVals = new ArrayList<>();
        for (BigInteger tipId : tipIds) { tipIdVals.add(new Uint256(tipId)); }
        return new org.web3j.abi.datatypes.DynamicArray<>(
                Uint256.class, tipIdVals);
    }

    private Function getTips(List<BigInteger> receivedTipIds)
    {
        return new Function("getTips",
                Arrays.asList(getTipIds(receivedTipIds)),
                Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<TipQuery>>() {}));
    }

    private Function getTipStatus(List<BigInteger> receivedTipIds)
    {
        return new Function("getTipStatus",
                Arrays.asList(getTipIds(receivedTipIds)),
                Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<Bool>>() {}));
    }

    //This is a ghastly hack, but since this part of web3j is broken this is the most expedient fix.
    //Implementing this in web3js you'd need to call like this:
    //[["Token address","Token amount","0x00"]],"Twitter ID" <-- NB we don't yet implement the 0x00 part. This is pre-auth and will be an auth
    private static String tipBytes(List<PaymentToken> tokens, String identifier)
    {
        String zeroes = "0000000000000000000000000000000000000000000000000000000000000000";
        String idHex = Numeric.toHexStringNoPrefix((new Utf8String(identifier)).getValue().getBytes(StandardCharsets.UTF_8));
        int ethWords = (idHex.length() / 64) + 1;
        idHex = idHex + zeroes;
        idHex = idHex.substring(0, ethWords*64);

        String function = "0x9218e0ee" + "0000000000000000000000000000000000000000000000000000000000000040";

        if (tokens.size() > 0)
        {
            function +=
                    "0000000000000000000000000000000000000000000000000000000000000120" +
                    "0000000000000000000000000000000000000000000000000000000000000001" +
                    "0000000000000000000000000000000000000000000000000000000000000020" +
                    "000000000000000000000000" + Numeric.cleanHexPrefix(tokens.get(0).address.getValue()) +
                    Numeric.toHexStringNoPrefixZeroPadded(tokens.get(0).value.getValue(), 64) +
                    "0000000000000000000000000000000000000000000000000000000000000060" +
                    "0000000000000000000000000000000000000000000000000000000000000001";
        }
        else
        { //special case if payment tokens is empty (ie only eth payment)
            function +=
                    "0000000000000000000000000000000000000000000000000000000000000060";
        }

        function += "0000000000000000000000000000000000000000000000000000000000000000" +
                Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(identifier.length()), 64) +
                idHex;

        return function;
    }

    protected static org.web3j.abi.datatypes.DynamicArray<?> getERC721Array(List<ERC721Token> tokens)
    {
        return new org.web3j.abi.datatypes.DynamicArray<ERC721Token>(
                ERC721Token.class, tokens);
    }

    protected static org.web3j.abi.datatypes.DynamicArray<?> getPaymentTokenArray(PaymentToken token)
    {
        if (token == null)
        {
            return new org.web3j.abi.datatypes.DynamicArray<>(PaymentToken.class, new ArrayList<>());
        }
        else
        {
            return new org.web3j.abi.datatypes.DynamicArray<>(
                    PaymentToken.class, Collections.singletonList(token));
        }
    }

    private static Function getHashNonce(String hashCode) {
        return new Function("getNonce",
                Arrays.asList(new Bytes32(Numeric.hexStringToByteArray(hashCode))),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
    }

    private static Function getGasToUse(String hashCode) {
        return new Function("getSendGas",
                Arrays.asList(new Bytes32(Numeric.hexStringToByteArray(hashCode))),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
    }

    private static Function getValue(String hash) {
        return new Function("getValue",
                Arrays.asList(new Bytes32(Numeric.hexStringToByteArray(hash))),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
    }

    private static Function verifyEncoding(byte[] com1, byte[] com2, byte[] encoding) {
        return new Function(
                "verifyEqualityProof",
                Arrays.asList(new DynamicBytes(com1), new DynamicBytes(com2), new DynamicBytes(encoding)),
                Collections.singletonList(new TypeReference<Bool>() {}));
    }

    //create NFT, returns NFT's TokenId
    private static Function createNFT(String destAddr, String tokenMetaDataBaseURI) {
        return new Function(
                "mintUsingSequentialTokenId",
                Arrays.asList(new Address(destAddr), new Utf8String(tokenMetaDataBaseURI)),
                Arrays.asList(new TypeReference<Uint256>() {}));
    }

    private static Function getTip(BigInteger tokenId) {
        return new Function(
                "getTip",
                Arrays.asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<PaymentToken>>() {},
                        new TypeReference<Address>() {}, new TypeReference<Uint256>() {},
                        new TypeReference<Utf8String>() {}, new TypeReference<Address>() {},
                        new TypeReference<Bool>() {}));
    }

    private BigInteger callFunction(Function function, String contractAddress, String fromAddress)
    {
        Web3j web3j = getWeb3j();

        BigInteger result = BigInteger.ZERO;

        try
        {
            String responseValue = callSmartContractFunction(web3j, function, contractAddress, fromAddress);
            List<Type> responseValues = FunctionReturnDecoder.decode(responseValue, function.getOutputParameters());

            if (!responseValues.isEmpty())
            {
                result = (BigInteger)responseValues.get(0).getValue();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return result;
    }

    private String callFunctionString(Function function)
    {
        Web3j web3j = getWeb3j();

        String result = "";

        try
        {
            result = callSmartContractFunction(web3j, function, CONTRACT, ZERO_ADDRESS);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return result;
    }

    //Used where contract returns a string
    private String callFunctionString(Function function, String contract)
    {
        Web3j web3j = getWeb3j();

        String result = "";

        try
        {
            result = callSmartContractFunction(web3j, function, contract, ZERO_ADDRESS);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        List<Type> responseValues = FunctionReturnDecoder.decode(result, function.getOutputParameters());

        if (!responseValues.isEmpty())
        {
            result = responseValues.get(0).getValue().toString();
        }

        return result;
    }

    private List callSmartContractFunctionArray(
            org.web3j.abi.datatypes.Function function, String contractAddress, String address) throws Exception
    {
        String value = callSmartContractFunction(getWeb3j(), function, contractAddress, address);

        List<Type> values = FunctionReturnDecoder.decode(value, function.getOutputParameters());
        if (values.isEmpty()) return null;

        Type T = values.get(0);
        Object o = T.getValue();
        return (List) o;
    }

    private String callSmartContractFunction(Web3j web3j,
                                             Function function, String contractAddress, String fromAddress)
    {
        String encodedFunction = FunctionEncoder.encode(function);

        try
        {
            org.web3j.protocol.core.methods.request.Transaction transaction
                    = createEthCallTransaction(fromAddress, contractAddress, encodedFunction);
            EthCall response = web3j.ethCall(transaction, DefaultBlockParameterName.LATEST).send();

            return response.getValue();
        }
        catch (IOException e)
        {
            return null;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] bytesFromSignature(Sign.SignatureData signature)
    {
        byte[] sigBytes = new byte[65];
        Arrays.fill(sigBytes, (byte) 0);

        try
        {
            System.arraycopy(signature.getR(), 0, sigBytes, 0, 32);
            System.arraycopy(signature.getS(), 0, sigBytes, 32, 32);
            System.arraycopy(signature.getV(), 0, sigBytes, 64, 1);
        }
        catch (IndexOutOfBoundsException e)
        {
            e.printStackTrace();
        }

        return sigBytes;
    }

    private static byte[] encode(RawTransaction rawTransaction, Sign.SignatureData signatureData) {
        List<RlpType> values = asRlpValues(rawTransaction, signatureData);
        RlpList rlpList = new RlpList(values);
        return RlpEncoder.encode(rlpList);
    }

    static List<RlpType> asRlpValues(
            RawTransaction rawTransaction, Sign.SignatureData signatureData) {
        List<RlpType> result = new ArrayList<>();

        result.add(RlpString.create(rawTransaction.getNonce()));
        result.add(RlpString.create(rawTransaction.getGasPrice()));
        result.add(RlpString.create(rawTransaction.getGasLimit()));

        // an empty to address (contract creation) should not be encoded as a numeric 0 value
        String to = rawTransaction.getTo();
        if (to != null && to.length() > 0) {
            // addresses that start with zeros should be encoded with the zeros included, not
            // as numeric values
            result.add(RlpString.create(Numeric.hexStringToByteArray(to)));
        } else {
            result.add(RlpString.create(""));
        }

        result.add(RlpString.create(rawTransaction.getValue()));

        // value field will already be hex encoded, so we need to convert into binary first
        byte[] data = Numeric.hexStringToByteArray(rawTransaction.getData());
        result.add(RlpString.create(data));

        if (signatureData != null) {
            result.add(RlpString.create(signatureData.getV()));
            result.add(RlpString.create(Bytes.trimLeadingZeroes(signatureData.getR())));
            result.add(RlpString.create(Bytes.trimLeadingZeroes(signatureData.getS())));
        }

        return result;
    }


    private ECKeyPair getAdminKeyPair()
    {
        byte[] adminPrivKey = Numeric.hexStringToByteArray(CONTRACT_KEY);
        ECKeyPair adminKey = ECKeyPair.create(adminPrivKey);
        return adminKey;
    }

    private Single<byte[]> signTransaction(ECKeyPair key, String toAddress, BigInteger value,
                                           BigInteger gasPrice, BigInteger gasLimit, long nonce, byte[] data,
                                           long chainId) {
        return Single.fromCallable(() -> {
            Sign.SignatureData sigData;
            String dataStr = data != null ? Numeric.toHexString(data) : "";

            RawTransaction rtx = RawTransaction.createTransaction(
                    BigInteger.valueOf(nonce),
                    gasPrice,
                    gasLimit,
                    toAddress,
                    value,
                    dataStr
            );

            byte[] signData = TransactionEncoder.encode(rtx, chainId);
            sigData = Sign.signMessage(signData, key);
            sigData = TransactionEncoder.createEip155SignatureData(sigData, chainId);
            return encode(rtx, sigData);
        }).subscribeOn(Schedulers.io());
    }

    private String load(String fileName) {
        String rtn = "";
        try {
            char[] array = new char[2048];
            FileReader r = new FileReader(fileName);
            r.read(array);

            rtn = new String(array);
            r.close();

        } catch (IOException e)
        {
            e.printStackTrace();
        }

        return rtn;
    }

    private String loadFile(String fileName) {
        byte[] buffer = new byte[0];
        try {
            InputStream in = getClass()
                    .getClassLoader().getResourceAsStream(fileName);
            buffer = new byte[in.available()];
            int len = in.read(buffer);
            if (len < 1) {
                throw new IOException("Nothing is read.");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return new String(buffer);
    }

    //1. This will get twittername, Base token amount and ERC20 amount

    @GetMapping(value = "/")
    public String createTip(@RequestHeader("User-Agent") String agent, Model model)
    {
        return "create_tip";
    }

    ///////////////////////////////////////////////////////
    // Create Tip Handling
    ///////////////////////////////////////////////////////

    //2. Verify User and test tip can be matched. If good, offer 'create tip'. If bad, show error page
    @GetMapping(value = "/createTipTx/{username}/{eth_amount}/{erc20_addr}/{erc20_amount}")
    String createTipTx(@PathVariable("username") String userName,
                       @PathVariable("eth_amount") String ethAmount,
                       @PathVariable("erc20_addr") String erc20Addr,
                       @PathVariable("erc20_amount") String erc20Amount,
                       Model model) {

        //fetch Twitter data
        TwitterData data = lookupTwitterName(userName);

        if (data == null)
        {
            model.addAttribute("userinput", userName);
            model.addAttribute("unknownUser", userName);
            return "create_tip";
        }

        BigDecimal offerVal = BigDecimal.ZERO;
        BigDecimal erc20Val = BigDecimal.ZERO;
        try {
            //check the eth amount:
            offerVal = new BigDecimal(ethAmount);
            offerVal = offerVal.multiply(WEI_FACTOR);
        }
        catch (Exception e)
        {
            //
        }

        try {
            if (WalletUtils.isValidAddress(erc20Addr))
            {
                erc20Val = new BigDecimal(erc20Amount).multiply(WEI_FACTOR); //TODO: Grab decimal value of erc20 and validate
            }
        }
        catch (Exception e)
        {
            //
        }

        if (offerVal.equals(BigDecimal.ZERO) && erc20Val.equals(BigDecimal.ZERO))
        {
            model.addAttribute("userinput", userName);
            model.addAttribute("requiretip", "error");
            return "create_tip";
        }

        //create transaction
        byte[] txBytes;
        String encodedFunction;
        String twitterId = data.getIdentifier();
        List<PaymentToken> pTokens = new ArrayList<>();
        if (erc20Val.compareTo(BigDecimal.ZERO) > 0)
        {
            pTokens.add(new PaymentToken(new Address(erc20Addr), new Uint256(erc20Val.toBigInteger()), new DynamicBytes(Numeric.hexStringToByteArray("0x00"))));
        }

        encodedFunction = tipBytes(pTokens, twitterId);
        txBytes = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(encodedFunction));

        model.addAttribute("profilepic", data.profile_image_url);
        model.addAttribute("username", data.username);
        model.addAttribute("id", data.id);
        model.addAttribute("eth", offerVal.toBigInteger().toString());
        model.addAttribute("eth_display", ethAmount);
        model.addAttribute("tx_bytes", "'" + Numeric.toHexString(txBytes) + "'");
        model.addAttribute("contract_address", "'" + CONTRACT + "'");
        model.addAttribute("gas_price", currentGasPrice.multiply(GWEI_FACTOR).toBigInteger().toString());
        model.addAttribute("gas_limit", GAS_LIMIT_CONTRACT.toString());
        model.addAttribute("expected_id", CHAIN_ID);
        model.addAttribute("expected_text", "'" + CHAIN_NAME + "'");

        if (erc20Val.compareTo(BigDecimal.ZERO) > 0)
        {
            model.addAttribute("erc20addr", "'" + erc20Addr + "'");
            model.addAttribute("erc20val", erc20Val.toBigInteger().toString());

            //need to call approve first
            //get approve ERC20 tx
            Function approve = approve(CONTRACT, erc20Val.toBigInteger());
            encodedFunction = FunctionEncoder.encode(approve);
            txBytes = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(encodedFunction));
            model.addAttribute("approve_tx", "'" + Numeric.toHexString(txBytes) + "'");
        }
        else
        {
            model.addAttribute("erc20addr", "' '");
            model.addAttribute("erc20val", "0");
            model.addAttribute("approve_tx", "' '");
        }

        return "processTransaction";
    }

    private class TwitterData
    {
        public String profile_image_url;
        public String name;
        public String id;
        public String username;

        public String getIdentifier()
        {
            return TWITTER_URL + username + " " + id;
        }
    }

    private class Encapsulate
    {
        TwitterData data;
    }

    private TwitterData lookupTwitterName(String twitterName)
    {
        OkHttpClient client = buildClient();
        String urlCall = "https://api.twitter.com/2/users/by/username/[USERNAME]?user.fields=profile_image_url".replace("[USERNAME]", twitterName);

        try
        {
            Request request = new Request.Builder()
                    .url(urlCall)
                    .method("GET", null)
                    .addHeader("Content-Type", "application/x-www-form-urlencoded")
                    .addHeader("Authorization", "Bearer " + TWITTER_BEARER_TOKEN)
                    .build();

            okhttp3.Response response = client.newCall(request).execute();
            //get result
            String result = response.body() != null ? response.body().string() : "";

            Encapsulate data = new Gson().fromJson(result, Encapsulate.class);
            if (data != null && data.data != null)
            {
                twitterIdMap.put(data.data.id, data.data);
                return data.data;
            }
        }
        catch (Exception e)
        {
            //
        }

        return null;
    }

    //3b. Error, not enough funds
    @GetMapping(value = "/notenoughfunds/")
    public String notEnoughFunds(Model model) {
        return "fund_error";
    }

    //3. Wait for approve transaction to be written to the blockchain,
    //   Then ask the user for the identity they wish to autograph the transaction
    //   and specify how much they offer to the identity for their autograph
    @GetMapping(value = "/waitForTip/{resulthash}")
    public String waitForTip(@PathVariable("resulthash") String resultHash,
                             Model model) {

        //wait for transaction to be written to block

        model.addAttribute("result_hash", "'" + resultHash + "'");
        model.addAttribute("collection_url", "'" + deploymentAddress + "claim" + "'");
        model.addAttribute("check_url", "'" + deploymentAddress + "checkTx/" + "'");

        return "tipCreated";
    }

    //See if transaction has been written
    @RequestMapping(value = "checkTx/{hash}", method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity checkTx(@PathVariable("hash") String hash,
                                        HttpServletRequest request) throws InterruptedException, ExecutionException, IOException {
        final Web3j web3j = getWeb3j();

        System.out.println("Check for Tx: " + hash);
        try {
            EthTransaction etx = web3j.ethGetTransactionByHash(hash).send();
            if (etx != null && etx.getResult().getBlockNumberRaw() != null && !etx.getResult().getBlockNumberRaw().equals("null")) {
                System.out.println("Tx written: " + etx.getResult().getHash());
                return new ResponseEntity<>("written", HttpStatus.CREATED);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new ResponseEntity<>("waiting", HttpStatus.CREATED);
    }

    //
    // Claim a tip
    //

    //1. Find User's tips /claim/{username} (generate QR code and link to give/show to user}
    //2. See if they have a cached attestation, if they do, move to 4, if not, generate attestation
    //3. Generate CosignedIdentifierAttestation
    //4. Push transaction.
    //5. TODO: Batch collect tips

    public Twitter getTwitter() {
        Twitter twitter = null;

        //build the configuration
        ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.setOAuthConsumerKey(TWITTER_API_KEY);
        builder.setOAuthConsumerSecret(TWITTER_KEY_SECRET);

        Configuration configuration = builder.build();

        //instantiate the Twitter object with the configuration
        TwitterFactory factory = new TwitterFactory(configuration);
        twitter = factory.getInstance();

        return twitter;
    }

    @GetMapping(value = "/claim")
    public String genAttestation(
                        Model model) {
        //login with twitter
        return "twitter_login";
    }

    @RequestMapping("/getToken")
    public RedirectView getToken(HttpServletRequest request, Model model) {
        //this will be the URL that we take the user to
        String twitterUrl = "";

        try {
            //get the Twitter object
            Twitter twitter = getTwitter();

            //get the callback url so they get back here
            String callbackUrl = deploymentAddress + "twitterCallback";

            //go get the request token from Twitter
            RequestToken requestToken = twitter.getOAuthRequestToken(callbackUrl);

            //put the token in the session because we'll need it later
            request.getSession().setAttribute("requestToken", requestToken);

            //let's put Twitter in the session as well
            request.getSession().setAttribute("twitter", twitter);

            //now get the authorization URL from the token
            twitterUrl = requestToken.getAuthorizationURL();

            System.out.println("Authorization url is " + twitterUrl);
        } catch (Exception e) {
            e.printStackTrace();//("Problem logging in with Twitter!", e);
        }

        //redirect to the Twitter URL
        RedirectView redirectView = new RedirectView();
        redirectView.setUrl(twitterUrl);
        return redirectView;
    }

    @RequestMapping("/twitterCallback")
    public @ResponseBody String twitterCallback(@RequestParam(value="oauth_verifier", required=false) String oauthVerifier,
                                  @RequestParam(value="denied", required=false) String denied,
                                  HttpServletRequest request, HttpServletResponse response, Model model) {

        if (denied != null) {
            //if we get here, the user didn't authorize the app
            return "redirect:twitterLogin";
        }

        //get the objects from the session
        Twitter twitter = (Twitter) request.getSession().getAttribute("twitter");
        RequestToken requestToken = (RequestToken) request.getSession().getAttribute("requestToken");

        try {
            AccessToken token = twitter.getOAuthAccessToken(requestToken, oauthVerifier);

            //take the request token out of the session
            request.getSession().removeAttribute("requestToken");

            //store the user name so we can display it on the web page
            TwitterData data = lookupTwitterName(twitter.getScreenName());
            if (attestationMap.containsKey(data.id)) {
                return showTipList(data.getIdentifier(), data.id);
            } else {
                String initHTML = loadFile("templates/getPublicKey.html");

                //store the user name so we can display it on the web page
                String imageBlock = "<img src=\"" + data.profile_image_url + "\" alt=\"" + data.username + "\" />\n" +
                        "<h5>" + data.username + "</h5>";

                initHTML = initHTML.replace("[IMAGE_BLOCK]", imageBlock);
                initHTML = initHTML.replace("[USERNAME]", data.username);
                initHTML = initHTML.replace("[IDENTIFIER]", data.id);

                return initHTML;
            }
        } catch (Exception e) {
            e.printStackTrace();
            //return "redirect:twitterLogin";
        }

        return "twitter_login";
    }

    //window.location.replace('/getPublicKey/' + account + '/' + identifier + '/' + result.result + '/' + msg + '/' + username);
    //3. generateAttestation; we need to ask user to sign a 'Personal Message' to recover the public key.
    // Once we have the publickey, we can create a SignedAttestation, then an NFTAttestation.
    // We then ask the user to sign the NFTAttestation, so that in step 4 we can create a SignedNFTAttestation
    @GetMapping(value = "/generateAttestation/{address}/{id}/{signature}/{message}/{username}")
    public String generateAttestation(@PathVariable("address") String address,
                                      @PathVariable("id") String id,
                                      @PathVariable("signature") String signature,
                                      @PathVariable("message") String message,
                                      @PathVariable("username") String username,
                                      Model model) throws IOException {

        byte[] encodedMessage = message.getBytes();
        byte[] compatibilityEncodedMessage = Numeric.hexStringToByteArray(message); //compatibility with MetaMask.
        byte[] sigBytes = Numeric.hexStringToByteArray(signature);
        Sign.SignatureData sd = sigFromByteArray(sigBytes);

        String addressRecovered = "";
        try
        {
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(encodedMessage, sd); // <-- recover sign personal message
            addressRecovered = "0x" + Keys.getAddress(publicKey);
            System.out.println("Recovered: " + addressRecovered);

            //now create PublicKey for attestation
            //////////////////// Using Attestation.id endpoint
            AsymmetricKeyParameter subjectPublicKey = SignatureUtility.recoverEthPublicKeyFromPersonalSignature(encodedMessage, sigBytes);

            //Check if we need MM compatibility mode
            if (!addressRecovered.equalsIgnoreCase(address))
            {
                //use compatibilityEncodedMessage
                publicKey = Sign.signedPrefixedMessageToKey(compatibilityEncodedMessage, sd); // <-- recover sign personal message
                addressRecovered = "0x" + Keys.getAddress(publicKey);
                System.out.println("Recovered: " + addressRecovered);
                //////////////////// Using Attestation.id endpoint
                subjectPublicKey = SignatureUtility.recoverEthPublicKeyFromPersonalSignature(compatibilityEncodedMessage, sigBytes);
            }

            //Generate the CosignedIdentifierAttestation
            SignedIdentifierAttestation att = AttestationHandler.createPublicAttestation(subjectPublicKey, id, TWITTER_URL + username);

            SubjectPublicKeyInfo spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(subjectPublicKey);

            //ask for signing now
            model.addAttribute("publickey", "'" + Numeric.toHexString(spki.getEncoded()) + "'");
            model.addAttribute("signedAttestation", "'" + Numeric.toHexString(att.getDerEncoding()) + "'"); //NB: we need to pass the raw attestation because 'Sign Personal' adds the PERSONAL prefix
            model.addAttribute("username", "'" + username + "'");
            model.addAttribute("id", "'" + id + "'");
        }
        catch (SignatureException e)
        {
            e.printStackTrace();
        }

        return "askForAttestationSignature";
    }

    @GetMapping(value = "/generatedCoSigned/{address}/{signature}/{publickey}/{signedAttestation}/{userName}/{id}")
    public @ResponseBody String generatedCoSigned(@PathVariable("address") String address,
                                     @PathVariable("signature") String signature,
                                     @PathVariable("publickey") String publickey,
                                     @PathVariable("signedAttestation") String signedAttestation,
                                     @PathVariable("userName") String userName,
                                     @PathVariable("id") String id,
                                     Model model) throws IOException, SignatureException
    {
        byte[] signedAttestationBytes = Numeric.hexStringToByteArray(signedAttestation);
        byte[] signatureBytes = Numeric.hexStringToByteArray(signature);
        //////////////////// Using Attestation.id endpoint (although you should already have this from step 3, it's the same object).
        AsymmetricKeyParameter subjectPublicKey = SignatureUtility.restoreKeyFromSPKI(Numeric.hexStringToByteArray(publickey));
        //////////////////// Using Attestation.id endpoint
        SignedIdentifierAttestation signedIdentifier = AttestationHandler.restoreSignedAttestation(signedAttestationBytes);

        CoSignedIdentifierAttestation coSigned = new CoSignedIdentifierAttestation(signedIdentifier, subjectPublicKey, signatureBytes);

        System.out.println("DER: " + Numeric.toHexString(coSigned.getDerEncoding()));

        //cache new attestation
        attestationMap.put(id, coSigned);

        String identifier = signedIdentifier.getUnsignedAttestation().getSubject();

        return showTipList(identifier, id);
    }

    @GetMapping(value = "/checkTipResults/{id}")
    public @ResponseBody String checkTipResults(@PathVariable("id") String id,
                                                  Model model) throws IOException, SignatureException
    {
        Map<BigInteger, Tip> tips = tipUserMap.get(id);

        if (tips == null)
        {
            return waitForTipResults(id);
        }
        else if (tips.size() == 0)
        {
            return loadFile("templates/noTips.html");
        }
        else
        {
            //show results
            String initHTML = loadFile("templates/selectTip.html");
            final BigDecimal weiFactor = BigDecimal.TEN.pow(18);

            StringBuilder tokenList = new StringBuilder();

            for (BigInteger tipId : tips.keySet()) {
                Tip tip = tips.get(tipId);
                tokenList.append("<h4>Tip ID #").append(tipId.toString()).append("</h4>");
                tokenList.append("<br/>");
                BigDecimal offer = (new BigDecimal(tip.weiValue)).divide(weiFactor);
                if (offer.compareTo(BigDecimal.ZERO) > 0) {
                    tokenList.append("Tip Eth Value: ").append("<b>").append(offer.toString()).append(" ETH</b><br/>");
                }
                if (tip.paymentTokens != null && tip.paymentTokens.length > 0)
                {
                    BigDecimal tokenOffer = (new BigDecimal(tip.paymentTokens[0].value.getValue())).divide(weiFactor); //TODO: Might not always be 18
                    if (tokenOffer.compareTo(BigDecimal.ZERO) > 0)
                    {
                        tokenList.append("Tip ERC20 Token: ").append("<b>").append(tip.paymentTokens[0].address).append("</b><br/>Amount:<b>").append(tokenOffer.toString()).append("</b><br/>");
                    }
                }
            }

            initHTML = initHTML.replace("[TIP_LIST]", tokenList.toString());
            initHTML = initHTML.replace("[USER_ID]", id);

            return initHTML;
        }
    }

    private String showTipList(final String fullIdentifier, final String id)
    {
        Single.fromCallable(() -> {
            //now build a list of tips
            int index = fullIdentifier.indexOf(TWITTER_URL);
            final String identifier = fullIdentifier.substring(index);

            Map<BigInteger, Tip> tips = getTipListForUser(identifier);

            tipUserMap.put(id, tips);
            return true;
        }).subscribeOn(Schedulers.io())
          .observeOn(Schedulers.io())
          .subscribe()
          .isDisposed();

        return waitForTipResults(id);
    }

    @RequestMapping(value = "getTipResults/{id}", method = { RequestMethod.GET, RequestMethod.POST })
    public ResponseEntity getTipResults(@PathVariable("id") String id,
                                        HttpServletRequest request) throws InterruptedException, ExecutionException, IOException
    {
        Map<BigInteger, Tip> tips = tipUserMap.get(id);

        if (tips == null)
        {
            return new ResponseEntity<>("waiting", HttpStatus.CREATED);
        }
        else
        {
            return new ResponseEntity<>("pass", HttpStatus.CREATED);
        }
    }

    private String waitForTipResults(String id) {
        String initHTML = loadFile("templates/findingTips.html");

        initHTML = initHTML.replace("[CHECK_URL]", deploymentAddress + "getTipResults/" + id);
        initHTML = initHTML.replace("[USER_ID]", id);

        return initHTML;
    }

    private Map<BigInteger, Tip> getTipListForUser(String identifier)
    {
        Map<BigInteger, Tip> tips = new HashMap<>();
        final Web3j web3j = getWeb3j();
        final Event event = getTipCreateEvent(); //search for 'CreateTip' events
        List<BigInteger> receivedTipIds = new ArrayList<>();

        try {
            DefaultBlockParameter startBlock = DefaultBlockParameterName.EARLIEST;
            EthFilter filter = getTipEventFilterByName(event, startBlock, identifier);
            EthLog logs = web3j.ethGetLogs(filter).send();
            //check logs to find tokenId
            if (logs != null && logs.getLogs().size() > 0)
            {
                for (EthLog.LogResult<?> ethLog : logs.getLogs())
                {
                    final EventValues eventValues = staticExtractEventParameters(event, (Log) ethLog.get()); //extract offerer, identifier, commitmentId
                    String tipIdStr = eventValues.getIndexedValues().get(2).getValue().toString(); //commitment ID (token ID of offer)
                    BigInteger tipId = new BigInteger(tipIdStr);
                    receivedTipIds.add(tipId);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        //find live tips
        List<Bool> liveTips = fetchLiveTips(receivedTipIds);

        for (int i = 0; i < receivedTipIds.size(); i++)
        {
            BigInteger tipId = receivedTipIds.get(i);
            Bool tipCompleted = liveTips.get(i);
            if (!tipCompleted.getValue())
            {
                Tip thisTip = fetchTipByID(tipId);
                tips.put(tipId, thisTip);
            }
        }

        return tips;
    }

    //1. Find user's tips
    @GetMapping(value = "/collectTip/{id}")
    public String claim(@PathVariable("id") String id,
                             Model model) {

        //pull tip and attestation
        CoSignedIdentifierAttestation att = attestationMap.get(id);
        Map<BigInteger, Tip> tips = tipUserMap.get(id);

        if (tips == null) { return "tipClaimed"; }

        //build list of tips to claim
        List<BigInteger> tipList = new ArrayList<>(tips.keySet());

        if (att == null || tipList.size() == 0)
        {
            return "error"; // TODO: show error
        }

        //form claim transaction for user to call
        Function claim = collectTip(tipList, att);
        tipUserMap.remove(id);

        String encodedFunction = FunctionEncoder.encode(claim);
        byte[] functionCode = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(encodedFunction));

        //now call the collectTip
        final BigInteger useGasPrice = currentGasPrice.multiply(GWEI_FACTOR).toBigInteger();

        String txHashStr = createTransaction(getAdminKeyPair(), CONTRACT, BigInteger.ZERO, useGasPrice, GAS_LIMIT_CONTRACT, functionCode, CHAIN_ID)
                .blockingGet();

        model.addAttribute("result_hash", "'" + txHashStr + "'");
        model.addAttribute("check_url", "'" + deploymentAddress + "checkTx/" + "'");
        return "tipClaimed";
    }

    //3. Wait for approve transaction to be written to the blockchain,
    //   Then ask the user for the identity they wish to autograph the transaction
    //   and specify how much they offer to the identity for their autograph
    @GetMapping(value = "/waitForClaim/{resulthash}")
    public String waitForClaim(@PathVariable("resulthash") String resultHash,
                             Model model) {

        //wait for transaction to be written to block
        waitForTransactionReceipt(resultHash);

        model.addAttribute("result_hash", "'" + resultHash + "'");

        return "tipClaimed";
    }


/*
    //4. Generate the commit function.
    //   This function specifies which token will be committed and the text identifier (eg @kingmidas). The offer price is bundled in the transaction 'value'
    //   function commitNFT(ERC721Token[] memory nfts, string memory identifier) payable external returns (uint256 commitmentId)
    @GetMapping(value = "/generateCommit/{tokenAddr}/{tokenId}/{gasPrice}/{offerValue}/{identifier}")
    public String generateCommit(@PathVariable("tokenAddr") String tokenAddr,
                                 @PathVariable("tokenId") String tokenId,
                                 @PathVariable("gasPrice") String gasPrice,
                                 @PathVariable("identifier") String identifier,
                                 @PathVariable("offerValue") String offerValue,
                                 Model model) {

        ERC721Token[] tokens = new ERC721Token[1];
        tokens[0] = new ERC721Token(tokenAddr, tokenId, new byte[]{0x00});

        List<ERC721Token> tt = new ArrayList<>();
        tt.add(new ERC721Token(tokenAddr, tokenId, new byte[]{0x00}));

        //We need to change the identifier to match what the IdentifierAttestation will encode:
        // This is now "https://twitter.com/[twitter name] [twitter numerical ID]" eg "https://twitter.com/metarinekby 12345678"
        String fullIdentifier = TWITTER_URL + identifier + " " + TWITTER_FAKE_UID;

        String encodedFunction = commitNFTBytes(tt, fullIdentifier);
        byte[] functionCode = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(encodedFunction));

        BigDecimal offerVal = new BigDecimal(offerValue);
        offerVal = offerVal.multiply(WEI_FACTOR);

        model.addAttribute("gas_price", gasPrice);
        model.addAttribute("gas_limit", GAS_LIMIT_CONTRACT);
        model.addAttribute("tx_bytes", "'" + Numeric.toHexString(functionCode) + "'");
        model.addAttribute("contract_address", "'" + RETORT_CONTRACT + "'");
        model.addAttribute("eth_value", offerVal);
        model.addAttribute("expected_id", CHAIN_ID);
        model.addAttribute("token_addr", "'" + tokenAddr + "'");
        model.addAttribute("token_id", tokenId);
        model.addAttribute("identifier", "'" + identifier + "'");

        return "pushCommitment";
    }

    //5. Wait for commitment transaction to be written to blockchain
    //   Once it has been written, read the event to find out the Token URI we need to write the Offer NFT metadata to.
    @GetMapping(value = "/waitForCommit/{resulthash}/{userAddress}/{tokenAddr}/{tokenId}/{identifier}")
    public String waitForCommit(@PathVariable("userAddress") String userAddr,
                                @PathVariable("resulthash") String resultHash,
                                @PathVariable("tokenAddr") String tokenAddr,
                                @PathVariable("tokenId") String tokenId,
                                @PathVariable("identifier") String identifier,
                                 Model model) {
        final Web3j web3j = getWeb3j();

        waitForTransactionReceipt(resultHash);

        final Event event = getCommitEvent();

        try {
            DefaultBlockParameter startBlock = getBlockNumber(resultHash); //get start block
            EthFilter filter = getCommitEventFilter(event, startBlock, userAddr);
            EthLog logs = web3j.ethGetLogs(filter).send();
            //check logs to find tokenId
            if (logs.getLogs().size() > 0) {
                for (EthLog.LogResult<?> ethLog : logs.getLogs()) {
                    String txHash = ((Log) ethLog.get()).getTransactionHash();
                    if (txHash.equals(resultHash)) {
                        final EventValues eventValues = staticExtractEventParameters(event, (Log) ethLog.get()); //extract offerer, identifier, commitmentId
                        //String identifier = eventValues.getIndexedValues().get(1).getValue().toString(); //identifier (eg @kingmidas)
                        String commitmentIdStr = eventValues.getIndexedValues().get(2).getValue().toString(); //commitment ID (token ID of offer)
                        BigInteger commitmentId = new BigInteger(commitmentIdStr);

                        String name = "Request for " + identifier + " to autograph.";
                        //fetch image URI from old token

                        Function tokenURIFunc = tokenURI(new BigInteger(tokenId));
                        String tokenURI = callFunctionString(tokenURIFunc, tokenAddr);

                        String imageURI = metaDataFromTokenURI(tokenURI).tokenImageURI;

                        //generate and store metadata for this commitment
                        createJsonMetadataForCommit(name, imageURI, commitmentId);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        model.addAttribute("commit_hash", "'" + resultHash + "'");
        return "commitmentComplete";  // Display the transaction hash and notify user commitment is complete
    }

    //
    // End Commit flow
    //




    ///////////////////////////////////////////////////////
    // Transmogrify Handling
    ///////////////////////////////////////////////////////

    //1. Get twitter handle and fetch offers to be signed (pull method)
    @GetMapping(value = "/signOffer/{address}")
    public String signOffer(@PathVariable("address") String address,
                            Model model)
    {
        //1. input the user's twitterID & locate corresponding requests to sign
        model.addAttribute("address", "'" + address + "'");
        return "signAttestation";
    }

    //2. Fetch offers using ethereum events
    @GetMapping(value = "/fetchOffers/{address}/{identifier}")
    public @ResponseBody String fetchOffers(@PathVariable("address") String address,
                                            @PathVariable("identifier") String identifier,
                                            Model model)
    {
        //form event filter
        final Web3j web3j = getWeb3j();
        final Event event = getCommitEvent(); //search for 'CreateCommitmentRequest' events
        List<Commitment> commitments = new ArrayList<>();

        BigDecimal weiFactor = BigDecimal.TEN.pow(18);

        StringBuilder tokenList = new StringBuilder();
        String initHTML = loadFile("templates/selectCommitment.html");

        try {
            //**Hu pls see1** Rebuild the identifier: In this case we need to use the full "https://twitter/[string id] [number id]"
            String fullIdentifier = TWITTER_URL + identifier + " " + TWITTER_FAKE_UID;
            DefaultBlockParameter startBlock = DefaultBlockParameterName.EARLIEST;
            EthFilter filter = getCommitEventFilterByName(event, startBlock, fullIdentifier);
            EthLog logs = web3j.ethGetLogs(filter).send();
            //check logs to find tokenId
            if (logs != null && logs.getLogs().size() > 0)
            {
                for (EthLog.LogResult<?> ethLog : logs.getLogs())
                {
                    final EventValues eventValues = staticExtractEventParameters(event, (Log) ethLog.get()); //extract offerer, identifier, commitmentId
                    String commitmentIdStr = eventValues.getIndexedValues().get(2).getValue().toString(); //commitment ID (token ID of offer)
                    BigInteger commitmentId = new BigInteger(commitmentIdStr);
                    Commitment commitment = fetchCommitmentFromID(commitmentId);

                    if (!commitment.completed)
                    {
                        //show open commitment
                        tokenList.append("<h4>Request ID #").append(commitmentId.toString()).append("</h4>");
                        tokenList.append("Sign Token: ").append(commitment.nfts[0].address).append(" (").append(commitment.nfts[0].tokenId.getValue().toString()).append(")");
                        tokenList.append("<br/>");
                        BigDecimal offer = (new BigDecimal(commitment.weiValue)).divide(weiFactor);
                        tokenList.append("Offer: ").append("<b>").append(offer.toString()).append(" ETH</b><br/>");
                    }
                }
            }
            else
            {
                tokenList.append("<h4>No current offers<h4/>");
            }

        } catch (Exception e) {
            e.printStackTrace();
            tokenList.append("<h4>Error: <h4/>").append("<br/>").append(e.getMessage());
        }

        initHTML = initHTML.replace("[COMMIT_LIST]", tokenList.toString());
        initHTML = initHTML.replace("[IDENTIFIER]", identifier);

        return initHTML;
    }

    private Commitment fetchCommitmentFromID(BigInteger commitmentId)
    {
        final Web3j web3j = getWeb3j();
        //fetch the commitment data from the retort contract
        Function commitmentFunc = getCommitment(commitmentId);
        String result = "";
        try
        {
            result = callSmartContractFunction(web3j, commitmentFunc, RETORT_CONTRACT, ZERO_ADDRESS);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        //form a commitment
        return new Commitment(commitmentFunc, result);
    }

    //3. generateAttestation; we need to ask user to sign a 'Personal Message' to recover the public key.
    // Once we have the publickey, we can create a SignedAttestation, then an NFTAttestation.
    // We then ask the user to sign the NFTAttestation, so that in step 4 we can create a SignedNFTAttestation
    @GetMapping(value = "/generateAttestation/{address}/{commitId}/{identifier}/{signature}/{message}")
    public String generateAttestation(@PathVariable("address") String address,
                                      @PathVariable("commitId") String commitId,
                                      @PathVariable("identifier") String identifier,
                                      @PathVariable("signature") String signature,
                                      @PathVariable("message") String message,
                                      Model model) throws IOException {
        //String msg = "Please sign this message to aquire your public attestation.";
        //byte[] encodedMessage = Numeric.toHexStringNoPrefix(msg.getBytes()).getBytes();// message //message.getBytes();
        //byte[] compatibilityEncodedMessage = msg.getBytes();//Numeric.hexStringToByteArray(message); //compatibility with MetaMask.
        byte[] encodedMessage = message.getBytes();
        byte[] compatibilityEncodedMessage = Numeric.hexStringToByteArray(message); //compatibility with MetaMask.
        System.out.println("YOLESS: " + Numeric.toHexString(compatibilityEncodedMessage));
        System.out.println("YOLESS: " + Numeric.toHexString(encodedMessage));
        byte[] sigBytes = Numeric.hexStringToByteArray(signature);
        Sign.SignatureData sd = sigFromByteArray(sigBytes);

        String addressRecovered = "";
        try
        {
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(encodedMessage, sd); // <-- recover sign personal message
            addressRecovered = "0x" + Keys.getAddress(publicKey);
            System.out.println("Recovered: " + addressRecovered);

            //now create PublicKey for attestation
            //////////////////// Using Attestation.id endpoint
            AsymmetricKeyParameter subjectPublicKey = SignatureUtility.recoverEthPublicKeyFromPersonalSignature(encodedMessage, sigBytes);

            //Check if we need MM compatibility mode
            if (!addressRecovered.equalsIgnoreCase(address))
            {
                //use compatibilityEncodedMessage
                publicKey = Sign.signedPrefixedMessageToKey(compatibilityEncodedMessage, sd); // <-- recover sign personal message
                addressRecovered = "0x" + Keys.getAddress(publicKey);
                System.out.println("Recovered: " + addressRecovered);
                //////////////////// Using Attestation.id endpoint
                subjectPublicKey = SignatureUtility.recoverEthPublicKeyFromPersonalSignature(compatibilityEncodedMessage, sigBytes);
            }

            //fetch commitment from Retort Contract
            Commitment commitment = fetchCommitmentFromID(new BigInteger(commitId));
            //Create attestor signed attestation. Note that the attestor key must be stored secretly on the server
            //////////////////// Using Attestation.id endpoint
            //**Hu pls see1** Using SignedIdentityAttestation now
            SignedIdentifierAttestation att = AttestationHandler.createPublicAttestation(subjectPublicKey, identifier);
            //////////////////// Using Attestation.id endpoint
            NFTAttestation nftAttestation = AttestationHandler.createNFTAttestation(att, commitment.nfts);
            //now that NFTAttestation has been created, we ask the signer to sign this

            SubjectPublicKeyInfo spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(subjectPublicKey);

            //ask for signing now
            model.addAttribute("publickey", "'" + Numeric.toHexString(spki.getEncoded()) + "'");
            model.addAttribute("nftAttestation", "'" + Numeric.toHexString(nftAttestation.getDerEncoding()) + "'"); //NB: we need to pass the raw attestation because 'Sign Personal' adds the PERSONAL prefix
            model.addAttribute("commitmentId", commitId);
        }
        catch (SignatureException e)
        {
            e.printStackTrace();
        }

        return "askForAttestationSignature";
    }

    //4. generateAttestation; User just signed the attestation using 'SignPersonal'. Add this sig, validate the attestation and continue
    @GetMapping(value = "/generatedSignedNFT/{address}/{commitId}/{signature}/{publickey}/{nftAttestation}")
    public String generatedSignedNFT(@PathVariable("address") String address,
                                     @PathVariable("commitId") String commitId,
                                     @PathVariable("signature") String signature,
                                     @PathVariable("publickey") String publickey,
                                     @PathVariable("nftAttestation") String nftAttestation,
                                     Model model) throws IOException, SignatureException
    {
        byte[] nftAttestationBytes = Numeric.hexStringToByteArray(nftAttestation);
        //////////////////// Using Attestation.id endpoint (although you should already have this from step 3, it's the same object).
        AsymmetricKeyParameter subjectPublicKey = SignatureUtility.restoreKeyFromSPKI(Numeric.hexStringToByteArray(publickey));
        //////////////////// Using Attestation.id endpoint
        NFTAttestation nftAtt = AttestationHandler.createNFTAttestation(nftAttestationBytes);

        BigDecimal currentGasPrice = getGasPriceGWEI();

        //create signed NFTAttestation
        //////////////////// Using Attestation.id endpoint
        SignedNFTAttestation signedNFTAttestation = new SignedNFTAttestation(nftAtt, subjectPublicKey, Numeric.hexStringToByteArray(signature));

        System.out.println("DER: " + Numeric.toHexString(signedNFTAttestation.getDerEncoding()));

        //now we have the signed attestation, we can push it through the transaction
        //Form Transmogrify function bytes:
        Function transmogrify = getTransmogrifyFunction(new BigInteger(commitId), signedNFTAttestation);
        String encodedFunction = FunctionEncoder.encode(transmogrify);
        byte[] functionCode = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(encodedFunction));

        model.addAttribute("tx_bytes", "'" + Numeric.toHexStringNoPrefix(functionCode) + "'");
        model.addAttribute("retort", "'" + RETORT_CONTRACT + "'");
        model.addAttribute("gas_price", currentGasPrice.multiply(GWEI_FACTOR).toBigInteger().toString());
        model.addAttribute("gas_limit", GAS_LIMIT_CONTRACT.toString());
        model.addAttribute("commit_id", commitId);

        return "pushTransmogrify";
    }


    //5. Wait for transmogrify call to be written, once written loop through all tokens which were created;
    //    write their token images and create metadata.
    @GetMapping(value = "/waitForTransmogrify/{resulthash}/{commitId}")
    public String waitForTransmogrify(@PathVariable("resulthash") String resultHash,
                                      @PathVariable("commitId") String commitId,
                                      Model model) {
        final Web3j web3j = getWeb3j();
        TextOverlay textOverlay = new TextOverlay();

        //let's create the new image
        //first load the metadata for the original commit
        final Commitment commitment = fetchCommitmentFromID(new BigInteger(commitId));
        waitForTransactionReceipt(resultHash);

        final Event event = getTransmogrifyEvent();

        //now that transaction has been written we can pull the events out and mint the MetaData
        try
        {
            EthFilter filter = getTransmogrifyEventFilter(event, new BigInteger(commitId));
            EthLog logs = web3j.ethGetLogs(filter).send();
            //check logs to find tokenId
            if (logs.getLogs().size() > 0) {
                for (EthLog.LogResult<?> ethLog : logs.getLogs()) {
                    String txHash = ((Log) ethLog.get()).getTransactionHash();
                    if (txHash.equals(resultHash)) {

                        final EventValues eventValues = staticExtractEventParameters(event, (Log) ethLog.get());
                        BigInteger newTokenId = new BigInteger(eventValues.getNonIndexedValues().get(0).getValue().toString());
                        Address originalTokenAddr = new Address(eventValues.getNonIndexedValues().get(1).getValue().toString());
                        BigInteger originalTokenId = new BigInteger(eventValues.getNonIndexedValues().get(2).getValue().toString());

                        //original Token MetaData
                        Function tokenURIFunc = tokenURI(originalTokenId);
                        String tokenURI = callFunctionString(tokenURIFunc, originalTokenAddr.toString());
                        TokenMetaData nfTmetaData = metaDataFromTokenURI(tokenURI);

                        String name = nfTmetaData.tokenName + " Signed by " + commitment.identifier;
                        //fetch image URI from old token & create new image with autograph
                        String md5image = textOverlay.makeTextOverlayFile(nfTmetaData.tokenImageURI, commitment.identifier);

                        createJsonMetadataForTransmogrify(name, md5image, newTokenId);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        model.addAttribute("commit_hash", "'" + resultHash + "'");
        return "transformComplete";
    }


    //
    // End Transmog flow
    //


 */

    /*
    private String pushCreateNFTTx(String destinationAddr, final String imageHash, final String name) {
        ECKeyPair adminKey = getAdminKeyPair();
        //form push transaction
        Function pocket = createNFT(destinationAddr, deploymentAddress + "tokendata/");

        //push Tx
        String encodedFunction = FunctionEncoder.encode(pocket);
        byte[] functionCode = Numeric.hexStringToByteArray(Numeric.cleanHexPrefix(encodedFunction));

        BigDecimal gasPrice = getGasPriceGWEI(); //TODO: Use price from background thread
        final BigInteger useGasPrice = gasPrice.multiply(GWEI_FACTOR).toBigInteger();

        String txHashStr = createTransaction(adminKey, NFT_TEST_CONTRACT, BigInteger.ZERO, useGasPrice, GAS_LIMIT_CONTRACT, functionCode, CHAIN_ID)
                .blockingGet();

        final Web3j web3j = getWeb3j();

        waitForTransactionReceipt(txHashStr);

        final Event event = getEvent();

        try {
            DefaultBlockParameter startBlock = getBlockNumber(txHashStr); //get start block
            EthFilter filter = getEventFilter(event, startBlock, destinationAddr);
            EthLog logs = web3j.ethGetLogs(filter).send();
            //check logs to find tokenId
            if (logs.getLogs().size() > 0) {
                for (EthLog.LogResult<?> ethLog : logs.getLogs()) {
                    String txHash = ((Log) ethLog.get()).getTransactionHash();
                    if (txHash.equals(txHashStr)) {
                        final EventValues eventValues = staticExtractEventParameters(event, (Log) ethLog.get());
                        String selectVal = eventValues.getIndexedValues().get(1).getValue().toString();
                        //Got Token ID!! Sweet
                        if (selectVal.length() > 0) {
                            BigInteger tokenId = new BigInteger(selectVal);
                            //now write the metadata
                            createJsonMetadata(name, imageHash, tokenId);
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return txHashStr;
    }

     */

    private DefaultBlockParameter getBlockNumber(String transactionHash)
    {
        DefaultBlockParameter startBlock = DefaultBlockParameterName.EARLIEST;
        BigInteger blockNum = hashToBlockNumber.get(transactionHash);
        hashToBlockNumber.remove(transactionHash);
        if (blockNum != null) startBlock = DefaultBlockParameter.valueOf(blockNum);
        return startBlock;
    }

    private Event getEvent()
    {
        List<TypeReference<?>> paramList = new ArrayList<>();
        paramList.add(new TypeReference<Address>(true) { });
        paramList.add(new TypeReference<Uint256>(true) { });

        return new Event("GenerateTokenId", paramList);
    }

    /*
    private EthFilter getEventFilter(Event event, DefaultBlockParameter startBlock, String destinationAddr)
    {
        final org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        startBlock,
                        DefaultBlockParameterName.LATEST,
                        NFT_TEST_CONTRACT) // contract address
                        .addSingleTopic(EventEncoder.encode(event));// NFT minted event format

        filter.addSingleTopic("0x" + TypeEncoder.encode(new Address(destinationAddr)));

        return filter;
    }*/

    private Event getCommitEvent()
    {
        List<TypeReference<?>> paramList = new ArrayList<>();
        paramList.add(new TypeReference<Address>(true) { });
        paramList.add(new TypeReference<Utf8String>(true) { });
        paramList.add(new TypeReference<Uint256>(true) { });

        return new Event("CreateCommitmentRequest", paramList);
    }

    private Event getTipCreateEvent()
    {
        List<TypeReference<?>> paramList = new ArrayList<>();
        paramList.add(new TypeReference<Address>(true) { });
        paramList.add(new TypeReference<Utf8String>(true) { });
        paramList.add(new TypeReference<Uint256>(true) { });

        return new Event("CreateTip", paramList);
    }

    private Event getTransmogrifyEvent()
    {
        List<TypeReference<?>> paramList = new ArrayList<>();
        paramList.add(new TypeReference<Utf8String>(true) { });
        paramList.add(new TypeReference<Uint256>(true) { });
        paramList.add(new TypeReference<Uint256>(false) { });
        paramList.add(new TypeReference<Address>(false) { });
        paramList.add(new TypeReference<Uint256>(false) { });
        return new Event("Transmogrify", paramList);
    }

    /*
    private EthFilter getCommitEventFilter(Event event, DefaultBlockParameter startBlock, String committerAddress)
    {
        final org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        startBlock,
                        DefaultBlockParameterName.LATEST,
                        RETORT_CONTRACT) // retort contract address
                        .addSingleTopic(EventEncoder.encode(event));// commit event format

        filter.addSingleTopic("0x" + TypeEncoder.encode(new Address(committerAddress)));

        return filter;
    }*/



    private TokenMetaData metaDataFromTokenURI(String tokenURI)
    {
        OkHttpClient httpClient = buildClient();
        TokenMetaData tmd = new TokenMetaData();
        String jsonResult = "";

        try
        {
            Request request = new Request.Builder()
                    .url(tokenURI)
                    .get()
                    .build();

            okhttp3.Response response = httpClient.newCall(request).execute();
            jsonResult = response.toString();
        }
        catch (InterruptedIOException e)
        {
            //If user switches account or network during a fetch
            //this exception is going to be thrown because we're terminating the API call
            //Don't display error
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (jsonResult.length() > 0)
        {
            tmd.fromJSON(jsonResult);
        }

        return tmd;
    }

    private void createJsonMetadata(String title, String pictureHash, BigInteger tokenId)
    {
        JSONObject obj = new JSONObject();
        obj.put("name", title);
        obj.put("image", deploymentAddress + "tokenimage/" + pictureHash);
        //TODO: more data here

        //write to file system
        storeMetaData(tokenId, obj.toString(), "");
    }

    private void createJsonMetadataForCommit(String title, String imageURI, BigInteger tokenId)
    {
        JSONObject obj = new JSONObject();
        obj.put("name", title);
        obj.put("image", imageURI);

        //write to file system
        storeMetaData(tokenId, obj.toString(), "commitments/");
    }

    private void createJsonMetadataForTransmogrify(String title, String pictureHash, BigInteger tokenId)
    {
        JSONObject obj = new JSONObject();
        obj.put("name", title);
        obj.put("image", deploymentAddress + "tokenimage/" + pictureHash);

        //write to file system
        storeMetaData(tokenId, obj.toString(), "autographed/");
    }

    private void storeMetaData(BigInteger tokenId, String tokenMetadata, String subDir)
    {
        try {
            File jsonData = new File(baseFilePath + subDir + tokenId.toString());

            FileOutputStream fos = new FileOutputStream(jsonData);
            OutputStream os = new BufferedOutputStream(fos);
            os.write(tokenMetadata.getBytes());
            fos.flush();
            os.close();
            fos.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    //1. Create NFT with JSON package. Upload a picture and title then sign transaction to create the NFT.
    //   This app creates the JSON that has attribute data and points to the uploaded image/title

    //2. Signing: Upload signature associated with Midas, take commitment ID and attestation.
    //   NFT is created in new package with commitmentID tokenID :- metadata points to signed attestation package. TODO: store at IPFS hash of pre-hash of attestation

    @PostMapping("/upload")
    public String uploadToLocalFileSystem(@RequestParam("file") MultipartFile file, Model model)
    {
        String newFilename = "unable to write " + StringUtils.cleanPath(file.getOriginalFilename());
        try {
            newFilename = copyToMD5(file, baseFilePath);
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        model.addAttribute("fileHash", newFilename);

        return "intermediateNFT";
    }

    @GetMapping(value = "/prepareToGenerate/{fileHash}")
    public String prepareToGenerate(@PathVariable("fileHash") String fileHash,
                                    Model model) {
        model.addAttribute("fileHash", fileHash);
        return "finalCreateNFT";
    }

    //store image as MD5 hash
    public String copyToMD5(MultipartFile file, String baseFilePath) throws IOException, NoSuchAlgorithmException {
        UUID uuid = UUID.randomUUID();
        Path path = Paths.get(baseFilePath + uuid.toString());
        Files.copy(file.getInputStream(), path, REPLACE_EXISTING);

        return moveToMD5(path.toFile());
    }

    public static String moveToMD5(File inputFile) throws IOException, NoSuchAlgorithmException
    {
        StringBuilder sb = new StringBuilder();
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] byteArray = new byte[1024];
        int bytesCount = 0;

        FileInputStream fis = new FileInputStream(inputFile);

        while ((bytesCount = fis.read(byteArray)) != -1) {
            digest.update(byteArray, 0, bytesCount);
        }

        byte[] bytes = digest.digest();
        for (byte aByte : bytes)
        {
            sb.append(Integer.toString((aByte & 0xff) + 0x100, 16).substring(1));
        }

        fis.close();
        Path hashPath = Paths.get(baseFilePath + sb.toString() + ".png");
        Files.move(inputFile.toPath(), hashPath, REPLACE_EXISTING);

        //return complete hash
        return sb.toString();
    }

    @RequestMapping(value = "/tokendata/{tokenid}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public void getTokenData(@PathVariable("tokenid") String tokenId, HttpServletResponse response) throws IOException
    {
        InputStream in = new FileInputStream(baseFilePath + tokenId);

        if (in.available() > 0) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            StreamUtils.copy(in, response.getOutputStream());
        }
    }

    @RequestMapping(value = "/commitments/{tokenid}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public void getCommitmentData(@PathVariable("tokenid") String tokenId, HttpServletResponse response) throws IOException
    {
        InputStream in = new FileInputStream(baseFilePath + "commitments/" + tokenId);

        if (in.available() > 0) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            StreamUtils.copy(in, response.getOutputStream());
        }
    }

    @RequestMapping(value = "/tokenimage/{tokenhash}", method = RequestMethod.GET,
            produces = MediaType.IMAGE_JPEG_VALUE)
    public void getImage(@PathVariable("tokenhash") String tokenHash, HttpServletResponse response) throws IOException
    {
        InputStream in = new FileInputStream(baseFilePath + tokenHash + ".png");

        if (in.available() > 0) {
            response.setContentType(MediaType.IMAGE_JPEG_VALUE);
            StreamUtils.copy(in, response.getOutputStream());
        }
    }

    @RequestMapping(value = "/wrapped/{tokenid}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public void getWrappedData(@PathVariable("tokenid") String tokenId, HttpServletResponse response) throws IOException
    {
        InputStream in = new FileInputStream(baseFilePath + "autographed/" + tokenId);

        if (in.available() > 0) {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            StreamUtils.copy(in, response.getOutputStream());
        }
    }

    private String fetchTokensFromOpensea(String address)
    {
        String jsonResult = "{\"noresult\":[]}";
        String apiBase = "https://rinkeby-api.opensea.io";
        StringBuilder sb = new StringBuilder();
        sb.append(apiBase);
        sb.append("/api/v1/assets/?owner=");
        sb.append(address);
        sb.append("&limit=" + 100);

        OkHttpClient httpClient = buildClient();

        try
        {
            Request request = new Request.Builder()
                    .url(sb.toString())
                    .get()
                    .build();

            okhttp3.Response response = httpClient.newCall(request).execute();
            jsonResult = response.toString();
        }
        catch (InterruptedIOException e)
        {
            //If user switches account or network during a fetch
            //this exception is going to be thrown because we're terminating the API call
            //Don't display error
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return jsonResult;
    }

    private EthFilter getTipEventFilterByName(Event event, DefaultBlockParameter startBlock, String forIdentifier)
    {
        final org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        startBlock,
                        DefaultBlockParameterName.LATEST,
                        CONTRACT) // retort contract address
                        .addSingleTopic(EventEncoder.encode(event));// commit event format

        //form keccak256 of identifier for event search (logs encode strings as keccak256 hash)
        Keccak.Digest256 digest = new Keccak.Digest256();
        byte[] digestBytes = digest.digest(forIdentifier.getBytes());

        filter.addSingleTopic(null); //committer address (can by any).
        filter.addSingleTopic(Numeric.toHexString(digestBytes)); //identifier of the "King Midas" that committer wanted to sign this NFT

        return filter;
    }

    private Tip fetchTipByID(BigInteger commitmentId)
    {
        final Web3j web3j = getWeb3j();
        //fetch the commitment data from the retort contract
        Function tipFunc = getTip(commitmentId);
        String result = "";
        try
        {
            result = callSmartContractFunction(web3j, tipFunc, CONTRACT, ZERO_ADDRESS);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        //form a commitment
        return new Tip(tipFunc, result);
    }

    /*private List<BigInteger> fetchLiveTips(List<BigInteger> receivedTipIds)
    {
        //fetch the commitment data from the retort contract
        Function tipFunc = getTips(receivedTipIds);
        List<TipQuery> result;
        try
        {
            result = callSmartContractFunctionArray(tipFunc, CONTRACT, ZERO_ADDRESS);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return new ArrayList<>();
    }*/

    private List<Bool> fetchLiveTips(List<BigInteger> receivedTipIds)
    {
        //fetch the commitment data from the retort contract
        Function tipFunc = getTipStatus(receivedTipIds);
        List<Bool> result;
        try
        {
            result = callSmartContractFunctionArray(tipFunc, CONTRACT, ZERO_ADDRESS);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            result = new ArrayList<>();
        }

        return result;
    }

    /*private EthFilter getTransmogrifyEventFilter(Event event, BigInteger commitId)
    {
        final org.web3j.protocol.core.methods.request.EthFilter filter =
                new org.web3j.protocol.core.methods.request.EthFilter(
                        DefaultBlockParameterName.EARLIEST,
                        DefaultBlockParameterName.LATEST,
                        RETORT_CONTRACT) // retort contract address
                        .addSingleTopic(EventEncoder.encode(event));// commit event format

        filter.addSingleTopic(null); //committer identifier
        filter.addSingleTopic(Numeric.toHexStringWithPrefixZeroPadded(commitId, 64)); // filter by this commitId - note there may be multiple logs

        return filter;
    }*/


    //Utility functions
    private void waitForTransactionReceipt(final String resultHash) {
        final Web3j web3j = getWeb3j();

        System.out.println("Wait for Tx: " + resultHash);
        while (!hashToBlockNumber.containsKey(resultHash)) {
            try {
                EthTransaction etx = web3j.ethGetTransactionByHash(resultHash).send();// .sendAsync().thenAccept(txDetails -> {
                if (etx != null && etx.getResult().getBlockNumberRaw() != null && !etx.getResult().getBlockNumberRaw().equals("null"))
                {
                    System.out.println("Tx written: " + etx.getResult().getHash());
                    hashToBlockNumber.put(resultHash, etx.getResult().getBlockNumber());
                    break;
                }
                sleep(500);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    //Test Code for dumping events

    private void testEvents2()
    {
        TextOverlay textOverlay = new TextOverlay();
        BigInteger newTokenId = new BigInteger("1");
        Address originalTokenAddr = new Address("0xa567f5A165545Fa2639bBdA79991F105EADF8522");
        BigInteger originalTokenId = new BigInteger("90");

        //original Token MetaData
        Function tokenURIFunc = tokenURI(originalTokenId);
        String tokenURI = callFunctionString(tokenURIFunc, originalTokenAddr.toString());
        TokenMetaData nfTmetaData = metaDataFromTokenURI(tokenURI);

        String name = nfTmetaData.tokenName + " Signed by " + "@kingmidas";
        //fetch image URI from old token & create new image with autograph
        String md5image = textOverlay.makeTextOverlayFile(nfTmetaData.tokenImageURI, "@kingmidas");

        createJsonMetadataForTransmogrify(name, md5image, newTokenId);
    }

    /*private void testEvents()
    {
        final Web3j web3j = getWeb3j();
        TextOverlay textOverlay = new TextOverlay();

        //let's create the new image
        //first load the metadata for the original commit
        final Commitment commitment = fetchCommitmentFromID(new BigInteger("1"));
        //waitForTransactionReceipt(resultHash);

        final Event event = getTransmogrifyEvent();

        //now that transaction has been written we can pull the events out and mint the MetaData
        try
        {
            EthFilter filter = getTransmogrifyEventFilter(event, new BigInteger("1"));
            EthLog logs = web3j.ethGetLogs(filter).send();
            //check logs to find tokenId
            if (logs.getLogs().size() > 0) {
                for (EthLog.LogResult<?> ethLog : logs.getLogs()) {
                    String txHash = ((Log) ethLog.get()).getTransactionHash();

                    final EventValues eventValues = staticExtractEventParameters(event, (Log) ethLog.get());
                    BigInteger newTokenId = new BigInteger(eventValues.getNonIndexedValues().get(0).getValue().toString());
                    Address originalTokenAddr = new Address(eventValues.getNonIndexedValues().get(1).getValue().toString());
                    BigInteger originalTokenId = new BigInteger(eventValues.getNonIndexedValues().get(2).getValue().toString());

                    //original Token MetaData
                    Function tokenURIFunc = tokenURI(originalTokenId);
                    String tokenURI = callFunctionString(tokenURIFunc, originalTokenAddr.toString());
                    TokenMetaData nfTmetaData = metaDataFromTokenURI(tokenURI);

                    String name = nfTmetaData.tokenName + " Signed by " + commitment.identifier;
                    //fetch image URI from old token & create new image with autograph
                    String md5image = textOverlay.makeTextOverlayFile(nfTmetaData.tokenImageURI, commitment.identifier);

                    createJsonMetadataForTransmogrify(name, md5image, newTokenId);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/


//    private String testImage() {
//        String URI = "";
//        Function tokenURIFunc = tokenURI(new BigInteger("17"));
//        String tokenURI = callFunctionString(tokenURIFunc, NFT_TEST_CONTRACT);
//        String imageURI = imageURIFromTokenURI(tokenURI);
//
//        System.out.println("Image: " + imageURI);
//
//        return URI;
//    }
}