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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
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
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction;
import static org.web3j.tx.Contract.staticExtractEventParameters;
import static tapi.api.CryptoFunctions.sigFromByteArray;

@Controller
@RequestMapping("/")
public class APIController
{
    private static final String CONTRACT = "0xE6aAf7C1bBD92B6FFa76ADF47816572EC9f5Ba76"; // Tipping contract TipOffer.sol on Rinkeby
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

    /***********************************
     * Create Tips
     ***********************************/

    //1. Ask user for Twitter username they want to tip, and get amount they want to tip
    @GetMapping(value = "/")
    public String createTip(@RequestHeader("User-Agent") String agent, Model model)
    {
        return "create_tip";
    }

    //2. Validate the Twitter user using the Twitter API routes. If user is available and tip is ok, proceed to create the tip
    //   TODO: Validate the input amount (Ethereum call will fail, but would be much better to warn user instead. MetaMask and AlphaWallet will warn user that TX might fail)
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

    //3b. TODO: Error, not enough funds
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

    //Pure API Route to support 'waitForTip' above (used in the <script> in waitForTip). See if transaction has been written
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

    /***********************************
     * Claim Tips
     ***********************************/

    //1. Ask user to login in with Twitter
    @GetMapping(value = "/claim")
    public String genAttestation(
                        Model model) {
        //login with twitter
        return "twitter_login";
    }

    //2. Handle the OAuth login with Twitter
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

    //3. Once Twitter has logged user in (or not) read the Twitter ID and commence tips lookup
    //   If user hasn't yet created an attestation, redirect user to begin attestation creation. Once attestation is complete we go to 'showTipsList'
    //   If user has created attestation, go immediately to fetching tips at 'showTipList'
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

    //4. generateAttestation; we asked user to sign a fixed message to discover their public key.
    // After we recover this we can generate a SignedIdentifierAttestation, which is signed by the AttestorKey (see AttestationHandler)
    // Note: the TipOffer contract has the address for the AttestorKey, and it ensures only attestations signed by this key work
    //
    // We now ask user to sign the SignedIdentifierAttestation, to create a CoSignedIdentifierAttestation which hard-locks the user's key to the AttestorKey and
    // the claimer's Twitter name and UID. This attestation is used within the contract to release the funds to the claimer's address recovered from the CoSignedIdentifierAttestation
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

    //5. User has signed the SignedIdentifierAttestation, now create the CoSignedIdentifierAttestation and store on the server for subsequent use
    //   Start scanning for tips, and show the 'looking for tips' screen with the flashing ... ***
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

    // Display the 'looking for tips' screen
    private String waitForTipResults(String id) {
        String initHTML = loadFile("templates/findingTips.html");

        initHTML = initHTML.replace("[CHECK_URL]", deploymentAddress + "getTipResults/" + id);
        initHTML = initHTML.replace("[USER_ID]", id);

        return initHTML;
    }

    // Get a list of active tips and store them in the mapping
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

    // Pure API route periodically called from the <script> section of the 'looking for tips' screen. Check if tips have been found yet
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

    //6. Display user's tips and show 'collect tips' button
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

    //7. Finally collect tips for the user and send them to the Subject address in the CoSignedIdentifierAttestation; ie the user's address
    //   Note, this screen polls the Ethereum node using 'checkTx' to see if transaction has been written. Once written it updates the page
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




    /***********************************
     * Twitter API routes
     ***********************************/

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

    // Get OAuth config for using Twitter sign-in
    public Twitter getTwitter()
    {
        Twitter twitter;

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



    /***********************************
     * Ethereum node comms
     ***********************************/

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



    /***********************************
     * Event Log pickup
     ***********************************/

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

    private Event getTipCreateEvent()
    {
        List<TypeReference<?>> paramList = new ArrayList<>();
        paramList.add(new TypeReference<Address>(true) { });
        paramList.add(new TypeReference<Utf8String>(true) { });
        paramList.add(new TypeReference<Uint256>(true) { });

        return new Event("CreateTip", paramList);
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



    /***********************************
     * Ethereum Web3j Function call generators
     ***********************************/

    private static Function approve(String adminAddress, BigInteger newAllowance)
    {
        return new Function("approve",
                Arrays.asList(new Address(adminAddress), new Uint256(newAllowance)),
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

    private static Function getTip(BigInteger tokenId) {
        return new Function(
                "getTip",
                Arrays.asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<PaymentToken>>() {},
                        new TypeReference<Address>() {}, new TypeReference<Uint256>() {},
                        new TypeReference<Utf8String>() {}, new TypeReference<Address>() {},
                        new TypeReference<Bool>() {}));
    }


    /***********************************
     * File handling
     ***********************************/

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
}