package tapi.api;

import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;

import java.util.ArrayList;
import java.util.List;

/*
    struct TipQuery {
        PaymentToken[] paymentTokens;
        uint256 weiValue;
        bool completed; // authorisation; null if underlying contract doesn't support it
    }
 */
public class TipQuery extends DynamicStruct {
    public PaymentToken[] paymentTokens;
    public Uint256 value;
    public Bool completed;

    public TipQuery(PaymentToken[] paymentTokens, Uint256 value, Bool completed) {
        this.paymentTokens = paymentTokens;
        this.value = value;
        this.completed = completed;
    }

    @Override
    public List<Type> getValue()
    {
        List<Type> tList = new ArrayList<>();
        tList.add(value);
        tList.add(completed);
        return tList;
    }

    @Override
    public String toString()
    {
        return "PaymentTokens: " + paymentTokens.length + " Value: " + value.getValue().toString() + " Completed: " + completed.toString();
    }

    @Override
    public String getTypeAsString() {
        return "TipQuery";
    }
}
