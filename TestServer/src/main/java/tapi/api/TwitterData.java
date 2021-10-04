package tapi.api;

import static tapi.api.APIController.TWITTER_URL;

public class TwitterData
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
