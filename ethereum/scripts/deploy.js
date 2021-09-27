const { ethers } = require("hardhat");
const { createWalletsAndAddresses, ethersDebugMessages } = require('./inc/lib');

(async ()=>{
    const {
        rinkebyDeployKey
    } = await createWalletsAndAddresses(ethers.provider);

    const VerifyAttestation = await ethers.getContractFactory("VerifyAttestation");
    const verifyAttestation = await VerifyAttestation.connect(rinkebyDeployKey).deploy(); //deploy with 'rinkebyDeployKey' key
    await verifyAttestation.deployed();
    console.log('[LOGIC CONTRACTS] --> Deployed VerifyAttestation');
    console.log('VerifyAttestation address: ' + verifyAttestation.address);
    console.log('User balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(rinkebyDeployKey.address)), "\n");

    const TwitterTipper = await ethers.getContractFactory("TipOffer");
    const tipperContract = await TwitterTipper.connect(rinkebyDeployKey).deploy(verifyAttestation.address);
    await tipperContract.deployed();

    console.log('[LOGIC CONTRACTS] --> Deployed TwitterTip');
    console.log('TwitterTip address: ' + tipperContract.address);
    console.log('User balance: ', ethers.utils.formatEther(await ethers.provider.getBalance(rinkebyDeployKey.address)), "\n");

})();
