# AlchemyTestServer
Test Server for Alchemy Wrapped NFT

Deployment Instructions:

Build:

```./gradlew build```

Deploy:

```java --jar /build/libs/alchemyserver.jar```

You will need to add the keys.secret to the directory above the root, eg:

...
/Dev/AlchemyTestServer/src
/Dev/AlchemyTestServer/build.gradle 
/Dev/keys.secret  <---

The keys.secret format is like this:
[Infura API key],[private key with Rinkeby on it],[root URL where you deploy],END_DATA

eg:
```876876aa878a787a878a787878b787b,87236487236487236478236478236487236487236487236487236487623487263487,http:///stormbird.duckdns.org/,END_DATA```

Finally, you need to create a './files' directory which has a './commitments' directory within it to serve the NFT metadata and images in this position, in the home directory:

/ (home)
/Dev
/Dev/AlchemyTestServer
/Dev/keys.secret
/files
/files/commitments

/files and /files/commitments are used to store ERC721 token metadata in them. You will see these directories filling up as NFTs are created.

Additionally; you will need to 
