# Alchemy TwitterTipper Server
Test Server for TwitterTipper

Deployment Instructions:

Build:

```./gradle build```

Deploy:

```./gradle bootRun```

You will need to add the keys.secret to the directory above the root of the github repo, eg:

...
/Dev/AlchemyTipping/TestServer/src
/Dev/AlchemyTipping/TestServer/build.gradle 
/Dev/keys.secret  <---

The keys.secret format is a simple CSV like this:
[Infura API key],[private key with Rinkeby on it to pay gas],[root URL where you deploy],[Attestation Eth Private Key Hex],[Twitter API Key],[Twitter API Secret],[Twitter Bearer Token],END_DATA

eg:
```876876aa878a787a878a787878b787b,87236487236487236478236478236487236487236487236487236487623487263487,http:///stormbird.duckdns.org/,FFFFFF87236487236478236478236487236487236487236487236487623487263487,QweRtyUiop3434QweRtyUiopQ,QweRtyUiop3434QweRtyUiopQQweRtyUiop3434QweRtyUiopQ,AAAAAAAAAAAAAAAAAQweRtyUiop3434QweRtyUiopQ%2QweRtyUiop3434QweRtyUiopQ%3QweRtyUiop3434QweRtyUiopQQweRtyUiop3434Qxxx,END_DATA```

The contract Rinkeby is here: ```0xE6aAf7C1bBD92B6FFa76ADF47816572EC9f5Ba76```.


## How it works

### Create a tip

1. Validate Social Network name and UID (as per AutographNFT).
2. Form the Full Social ID String (eg https://twitter.com/cryptonomicon 12345678).
3. Get Eth amount and/or ERC20 token tip from user. Note that for entering ERC20 amount from user you need to multiply by decimals of contract.
4. If there is ERC20 amount, call 'approve' on that amount for the Tipping contract.
5. Call the 'createTip' function in the contract:

```createTip(PaymentToken[] memory paymentTokens, string memory identifier)``` with any eth value attached to the transaction.

```
    struct PaymentToken {
        address erc20; 
        uint256 amount;
        bytes auth; // authorisation; null if underlying contract doesn't support it
    }
```	

Note: Call will revert if the attached eth value is zero and there are no ERC20 tokens.

This creates an event with the following signature:

```event CreateTip(address indexed offerer, string indexed identifier, uint256 indexed tipId)```

Where: 
```offerer``` is the address of the account that created the tip.
```identifier``` is the Full Social ID String.
```tipId``` is the numeric ID of the tip entry.


### Claim a tip

1. Perform OAuth authentication to build the validated Full Social ID String.
2. If user has previously created a CoSignedIdentifierAttestation, used the cached one and go to 8.
3. Obtain user's PublicKey as per AutographNFT signing request.
4. Build SignedIdentifierAttestation as per AutographNFT.
5. Ask user to do SignPersonal on the SignedIdentifierAttestation (similar to user signing the NFTAttestation in AutographNFT).
6. Use the signature to build a CoSignedIdentifierAttestation in Attestation.id
7. Cache the CoSignedIdentifierAttestation against the user's Full Social ID String.
8. Query the event logs to find all events with matching ```identifier``` for the Full Social ID String. Query 'getTips' for each to determine if the tip is still valid (or use logs below).
9. Form an array of all the current tips.
10. Call the ```collectTip(uint256[] tipIds, bytes memory coSignedAttestation)```
---

Note 1: In step 10, for the planned implementation there will be a maximum value for the tipId array size. 
This can be determined by doing an ```estimateGas``` call to a node and seeing if it exceeds the maximum contract call gas. If it exceeds, then try 1/2 of the tips etc.

### Supplementary functions and events
```getTip(uint256 tipId) returns (PaymentToken[] memory paymentTokens, address offerer, uint256 weiValue, string memory identifier, address payee, bool completed)```

 Gives you extended details of the current tips.
 
 ```getTips(uint256[] tipId) returns (TipQuery[] memory tips)```
 ```
     struct TipQuery {
        PaymentToken[] paymentTokens;
        uint256 weiValue;
        bool completed; // authorisation; null if underlying contract doesn't support it
    }
 ```

 Queries an array of TipIDs to give the collected/live status of a tip along with its value.
 
 ```getTipStatus(uint256[] tipIds) returns (bool[] completed)```

 Gives you the completed/available status of an array of Tip IDs

```cancelTip(uint256 tipId)```

 Allows tip creator to withdraw their tip, getting their eth and tokens back.
 
```verifyAttestation(bytes memory attestation) returns(string memory identifier, address attestorAccount, address userAccount)```
 
 Utility function to check a created CoSignedIdentifierAttestation
 
```event CollectTip(address indexed offerer, string indexed identifier, uint256 indexed tipId, address tipee)```

 Generated when a tip is collected, giving information about the identifier, id and address of collector. Can be used together with the ```createTip``` event to determine valid tips
 
```event CancelTip(address indexed offerer, string indexed identifier, uint256 indexed tipId)```

 Generated whenever a tip is cancelled.
