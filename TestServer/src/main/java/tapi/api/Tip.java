package tapi.api;

import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;

import java.math.BigInteger;
import java.util.List;

public class Tip {
    final PaymentToken[] paymentTokens;
    final BigInteger weiValue; // any chain value in Wei
    final String offerer;
    final String identifier;
    final String payee;
    final boolean completed;

    public Tip(Function commitmentFunc, String result)
    {
        List<Type> responseValues = FunctionReturnDecoder.decode(result, commitmentFunc.getOutputParameters());
        List<PaymentToken> pTokens = (List<PaymentToken>)responseValues.get(0).getValue();
        paymentTokens = pTokens.toArray(new PaymentToken[0]);
        offerer = responseValues.get(1).getValue().toString();
        weiValue = (BigInteger) responseValues.get(2).getValue();
        identifier = responseValues.get(3).getValue().toString();
        payee = responseValues.get(4).getValue().toString();
        completed = (boolean)responseValues.get(5).getValue();
    }
}
