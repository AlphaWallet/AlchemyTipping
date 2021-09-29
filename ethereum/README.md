## Content

## Usage

### Install Dependencies

```shell
npm install
```

### Test

```shell
npm test
```

### Deploy VerifyAttestation, AlchemyRemix(UUPS), AlchemyRetort(UUPS) 

1. create ethereum/.env file
2. add API keys and private keys for account, which has to deploy contracts. 
`.env` content variables.
3. Deploy

#### Example of etherem/.env file

````
PRIVATE_KEY_GOVERNANCE = "0xdecafbad000000000000000000000000000000000000000000000000000000"
PRIVATE_KEY_MAIN =       "0xfedbad00000000000000000000000000000000000000000000000000000000"
PRIVATE_KEY_MY_NFT =     "0xdecafbadbad000000000000000000000000000000000000000000000000000"
PRIVATE_KEY_RETORT =     "0xfedbadbad00000000000000000000000000000000000000000000000000000"
PRIVATE_KEY_REMIX =      "0xdecaffedbad000000000000000000000000000000000000000000000000000"
ALCHEMY_ROPSTEN_API_KEY="yiMZHm…"  
ALCHEMY_RINKEBY_API_KEY="lUmLTt…"  
ALCHEMY_API_KEY="lUmLTtUR0…"
````

#### Deploy to the Local Hardhat node:

```shell
npx hardhat run scripts/deploy-all.js
```

#### Deploy to the ganache-cli node
```shell
npx hardhat run scripts/deploy-all.js --network localhost
```

#### Deploy to the ropsten testNet (same for rinkeby)
```shell
npx hardhat run scripts/deploy-all.js --network localhost
```

# Deployed contracts

## Production deployments

(Utility contracts such as attestation verification contract is not shown in this table)

| Contract Name   | Address                                      | mainnet                                                      | BSC                                                          | xDAI                                                         | Polygon                                                      |
| --------------- | -------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| Retort Contract | `0x1111111111acCdF36DbeE012FC26BB9fcC1D140D` | [etherscan](https://etherscan.io/address/0x1111111111accdf36dbee012fc26bb9fcc1d140d) | [bscscan](https://bscscan.com/address/0x1111111111accdf36dbee012fc26bb9fcc1d140d) | [blockscout](https://blockscout.com/xdai/mainnet/address/0x1111111111acCdF36DbeE012FC26BB9fcC1D140D/transactions) | [polygonscan](https://polygonscan.com/address/0x1111111111acCdF36DbeE012FC26BB9fcC1D140D) |
| Remix Contract  | `0x222222222291749DE47895C0c0A9B17e4fcA8268` | [etherscan](https://etherscan.io/address/0x222222222291749DE47895C0c0A9B17e4fcA8268) | [bscscan](https://bscscan.com/address/0x222222222291749DE47895C0c0A9B17e4fcA8268) | [blockscout](https://blockscout.com/xdai/mainnet/address/0x222222222291749DE47895C0c0A9B17e4fcA8268) | [polygonscan](https://polygonscan.com/address/0x222222222291749DE47895C0c0A9B17e4fcA8268) |
| MyNFT Contract  | `0x000000000437b3CCE2530936156388Bff5578FC3` | [etherscan](https://etherscan.io/address/0x000000000437b3CCE2530936156388Bff5578FC3) | [bscscan](https://bscscan.com/address/0x000000000437b3CCE2530936156388Bff5578FC3) | [blockscout](https://blockscout.com/xdai/mainnet/address/0x000000000437b3CCE2530936156388Bff5578FC3) | [polygonscan](https://polygonscan.com/address/0x000000000437b3CCE2530936156388Bff5578FC3) |
| DvP contract    | `0xdDdDdDdDdAf8297C939aD09989e44DD06A21A3Cb` | [etherscan](https://etherscan.io/address/0xdDdDdDdDdAf8297C939aD09989e44DD06A21A3Cb) | [bscscan](https://bscscan.com/address/0xdDdDdDdDdAf8297C939aD09989e44DD06A21A3Cb) | [blockscout](https://blockscout.com/xdai/mainnet/address/0xdDdDdDdDdAf8297C939aD09989e44DD06A21A3Cb) | [polygonscan](https://polygonscan.com/address/0xdDdDdDdDdAf8297C939aD09989e44DD06A21A3Cb) |

The fee multisig
| Network   | Address                                      | 
| --------------- | -------------------------------------------- | 
| mainnet    | `0x16cA54aFA80695a134F2E80d727288127c86b2aa` | 
| BSC    | `0x1aA9Ac156218FdA784517E07ba4A851cE829b27b` | 
| xDAI    | `0x53963baee5730527811dff0355082af1A5a35DeE` | 
| Polygon    | `0xF46089bfC3B0a0448B409F8B61925dc915beEc9F` | 


2/5 Owners:
| Address   |
| --------------- |
| 0xfA6FFce8d7671FcDd2AF5007f3F40c96B1C227Ea |
| 0xbc8dAfeacA658Ae0857C80D8Aa6dE4D487577c63 |
| 0xC067A53c91258ba513059919E03B81CF93f57Ac7 |
| 0xfCABe3451aC8EDfB8FB6b9274C2E095D9cCC8082 |
| 0x147615dCEb7AAC2E7389037300b65e99B3b94F96 |




## Testnet deployment

```
Owner            : 0x2F21dC12dd43bd15b86643332041ab97010357D7(not updated) 
```

Note: MyNFT is Mintable ERC721 with no lock on mint permission - user can push their own minting function, just have to create the function code for them, function name is the same: mintUsingSequentialTokenId(address to)
### Rinkeby and Matic’s Mumbai-Testnet

```
DvP               : 0xdB33597031dA8855BedC3074390031b8c72d8697
VerifyAttestation : 0x0000857D287F32eA0B9e2c418D0401ade4c60883
Remix proxy       : 0x000080C09264F55160b719d6700424b877C8d977
Retort proxy      : 0x0000AD880efD1690EFd57Da2929bEA1d6eF221Ec
MyNFT             : 0x0000853AbE6Fa93f276b62F6984B75Ff2f9dC30E
```
