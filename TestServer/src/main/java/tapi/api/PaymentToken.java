package tapi.api;

import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.DynamicStruct;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class PaymentToken extends DynamicStruct {
    public Address address;
    public Uint256 value;
    public DynamicBytes auth;

    public PaymentToken(Address address, Uint256 value, DynamicBytes bytes) {
        super(address, value);
        this.address = address;
        this.value = value;
        this.auth = bytes;
    }

    public PaymentToken(String address, String value) {
        super(new Address(address), new Uint256(new BigInteger(value)));
        this.address = new Address(address);
        this.value = new Uint256(new BigInteger(value));
        this.auth = new DynamicBytes(new byte[] { 0x00 });
    }

    @Override
    public List<Type> getValue()
    {
        List<Type> tList = new ArrayList<>();
        tList.add(address);
        tList.add(value);
        return tList;
    }

    @Override
    public String toString()
    {
        return "Token: " + address.toString() + " Value: " + value.getValue().toString();
    }

    @Override
    public String getTypeAsString() {
        return "PaymentToken";
    }
}
