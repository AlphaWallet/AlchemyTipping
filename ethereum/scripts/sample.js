const { ethers, upgrades } = require("hardhat");
const { createWalletsAndAddresses } = require('./inc/lib');

(async ()=>{
    const {
        mainDeployKey,
        retortDeployKey,
        myNFTDeployKey,
        remixDeployKey,
        RetortProxyAddress
    } = await createWalletsAndAddresses(ethers.provider);

    const DvP = await ethers.getContractFactory("DvP");
    const dvp = await DvP.connect(mainDeployKey).deploy(); //deploy with 'mainDeployKey' key
    await dvp.deployed();
    console.log("[LOGIC CONTRACTS] --> Deployed DvP");
    console.log('DvP address: ' + dvp.address);
    console.log( 'User balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(mainDeployKey.address)), "\n");

    const VerifyAttestation = await ethers.getContractFactory("VerifyAttestation");
    const verifyAttestation = await VerifyAttestation.connect(mainDeployKey).deploy(); //deploy with 'mainDeployKey' key
    await verifyAttestation.deployed();
    console.log('[LOGIC CONTRACTS] --> Deployed VerifyAttestation');
    console.log('VerifyAttestation address: ' + verifyAttestation.address);
    console.log('User balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(mainDeployKey.address)), "\n");

    console.log( 'retort calc addr: ' , RetortProxyAddress, "\n");

    const myNFTTokens = await ethers.getContractFactory("MyNFT");
    const proxyNFTTokens = await upgrades.deployProxy(myNFTTokens.connect(myNFTDeployKey), { kind: 'uups' }); //deploy with 'myNFTDeployKey' key
    await proxyNFTTokens.deployed();
    console.log("[PROXY & LOGIC CONTRACTS] --> Deployed MyNFT");
    console.log('MyNFT proxy address: ' + proxyNFTTokens.address);

    const AlchemyRemix = await ethers.getContractFactory("AlchemyRemix");
    const proxyRemix = await upgrades.deployProxy(AlchemyRemix.connect(remixDeployKey), [RetortProxyAddress, verifyAttestation.address], { kind: 'uups' });
    await proxyRemix.deployed();
    console.log("[PROXY & LOGIC CONTRACTS] --> Deployed AlchemyRemix");
    console.log('Remix proxy address: ' + proxyRemix.address);
    console.log( 'Remix deploy balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(remixDeployKey.address)), "\n");

    const AlchemyRetort = await ethers.getContractFactory("AlchemyRetort");
    // proxyRetort = await upgrades.deployProxy(AlchemyRetort,[verifyAttestation.address, proxyRemix.address, randomAddress] ,{ kind: 'uups' });
    const proxyRetort = await upgrades.deployProxy(AlchemyRetort.connect(retortDeployKey),[verifyAttestation.address, dvp.address, proxyRemix.address] ,{ kind: 'uups' });
    await proxyRetort.deployed();
    console.log("[PROXY & LOGIC CONTRACTS] --> Deployed AlchemyRetort");
    console.log('Retort proxy address: ' + proxyRetort.address);
    console.log( 'Retort deploy balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(retortDeployKey.address)), "\n");

    await dvp.setRetort(proxyRetort.address);
    console.log('DvP setRetort with retort address');
})();
