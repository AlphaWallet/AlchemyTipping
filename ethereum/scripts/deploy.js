const { ethers } = require("hardhat");
const { createWalletsAndAddresses, ethersDebugMessages } = require('./inc/lib');

(async ()=>{
    const {
        rinkebyDeployKey
    } = await createWalletsAndAddresses(ethers.provider);

    const debugAttestorAddress = '0x5f7bFe752Ac1a45F67497d9dCDD9BbDA50A83955';

    const VerifyAttestation = await ethers.getContractFactory("VerifyAttestation");
    const verifyAttestation = await VerifyAttestation.connect(rinkebyDeployKey).deploy(); //deploy with 'rinkebyDeployKey' key
    await verifyAttestation.deployed();
    console.log('[LOGIC CONTRACTS] --> Deployed VerifyAttestation');
    console.log('VerifyAttestation address: ' + verifyAttestation.address);
    console.log('User balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(rinkebyDeployKey.address)), "\n");

    const TwitterTipper = await ethers.getContractFactory("TipOffer");
    const proxyTipper = await upgrades.deployProxy(TwitterTipper,[verifyAttestation.address] ,{ kind: 'uups' });
    await proxyTipper.deployed();

    //set contract to use debug attestation key
    await proxyTipper.setAttestor(debugAttestorAddress);

    console.log('[LOGIC CONTRACTS] --> Deployed TwitterTip');
    console.log('TwitterTip address: ' + proxyTipper.address);
    console.log('User balance: ', ethers.utils.formatEther(await ethers.provider.getBalance(rinkebyDeployKey.address)), "\n");

})();
