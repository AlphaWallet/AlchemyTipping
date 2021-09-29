const { ethers } = require("hardhat");
const { createWalletsAndAddresses, ethersDebugMessages } = require('./inc/lib');

(async ()=>{
    const {
        rinkebyDeployKey
    } = await createWalletsAndAddresses(ethers.provider);

    const PaymentToken = await ethers.getContractFactory("StableCoin");
    const paymentToken = await PaymentToken.connect(rinkebyDeployKey).deploy(); //deploy with 'rinkebyDeployKey' key
    await paymentToken.deployed();
    console.log('PaymentToken address: ' + paymentToken.address);
    console.log('User balance: ' , ethers.utils.formatEther(await ethers.provider.getBalance(rinkebyDeployKey.address)), "\n");

})();