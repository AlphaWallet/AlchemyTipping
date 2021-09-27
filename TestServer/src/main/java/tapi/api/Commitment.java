package tapi.api;

import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Commitment {
    final ERC721Token[] nfts;
    final PaymentToken[] paymentTokens;
    final BigInteger weiValue; // any chain value in Wei
    final String offerer;
    final String identifier;
    final boolean completed;

    public Commitment(Function commitmentFunc, String result)
    {
        List<Type> responseValues = FunctionReturnDecoder.decode(result, commitmentFunc.getOutputParameters());
        List<ERC721Token> tokens = (List<ERC721Token>)responseValues.get(0).getValue();
        nfts = tokens.toArray(new ERC721Token[0]);
        List<PaymentToken> pTokens = (List<PaymentToken>)responseValues.get(1).getValue();
        paymentTokens = pTokens.toArray(new PaymentToken[0]);
        offerer = responseValues.get(2).getValue().toString();
        weiValue = (BigInteger) responseValues.get(3).getValue();
        identifier = responseValues.get(4).getValue().toString();
        completed = (boolean)responseValues.get(5).getValue();
    }
}
