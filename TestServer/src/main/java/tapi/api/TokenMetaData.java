package tapi.api;

import org.json.JSONObject;

public class TokenMetaData
{
    public String tokenName;
    public String tokenImageURI;

    public TokenMetaData()
    {
        tokenName = "";
        tokenImageURI = "";
    }

    public void fromJSON(String jsonResult) {
        JSONObject uriData = new JSONObject(jsonResult);
        if (uriData.has("image"))
        {
            tokenImageURI = uriData.getString("image");
        }
        if (uriData.has("name"))
        {
            tokenName = uriData.getString("name");
        }
    }
}
