const { ethers, upgrades } = require("hardhat");
const { createWalletsAndAddresses, ethersDebugMessages } = require('./inc/lib');

(async ()=>{
    const {
        rinkebyDeployKey,
        rinkebyDeployKey2
    } = await createWalletsAndAddresses(ethers.provider);

    const ProxyTipperAddress = "0xE6aAf7C1bBD92B6FFa76ADF47816572EC9f5Ba76";
    const attestorAddress = "0x6f5547825d7F2A6F7711B5bc1b152f1588a1Fc9e";

    const TwitterTipper = await ethers.getContractFactory("TipOffer");

    let proxyTipper = TwitterTipper.attach(ProxyTipperAddress);
    let ownerAddress = await proxyTipper.owner();

    const { getImplementationAddress } = require('@openzeppelin/upgrades-core');
    const currentImplAddress = await getImplementationAddress(ethers.provider, ProxyTipperAddress);
    console.log("[PROXY & LOGIC CONTRACTS] --> TwitterTipper Logic Address: ", currentImplAddress);

    if (ownerAddress.toLowerCase() !== rinkebyDeployKey2.address.toLowerCase()) {
        console.log(`deployKey doesnt equal to the contract.owner(): (${ownerAddress} vs ${rinkebyDeployKey2.address}), execution stopping...`);
        return;
    }

    try {
        await proxyTipper.connect(rinkebyDeployKey2).setAttestor(attestorAddress);
        console.log("[PROXY & LOGIC CONTRACTS] --> TwitterTipper Attestation Key Updated to ", attestorAddress);
    } catch (e) {
        ethersDebugMessages('TwitterTipper upgrade FAILED', e)
    }

    console.log('Deploy Key balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(rinkebyDeployKey2.address)), "\n");

})();
