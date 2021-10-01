import {parseEthers} from "../../EthUtils";
const { ethers, upgrades } = require('hardhat');

import { SignerWithAddress } from "@nomiclabs/hardhat-ethers/signers";
import { expect } from "chai";
import {BigNumber, Contract} from "ethers";
import exp from "constants";

describe("TwitterTipper.deploy", function () {
    let verifyAttestation: Contract;
    let stableCoin: Contract;
    let proxyTipper: Contract;

    let owner: SignerWithAddress;
    let addr1: SignerWithAddress;
    let addr2: SignerWithAddress;
    let testAddr: SignerWithAddress;
    let testAddr2: SignerWithAddress;
    let deployAddr: SignerWithAddress;

    let randomAddress = "0x538080305560986811c3c1A2c5BCb4F37670EF7e";
    let randomUserAddress = "0x0C770da98559DD6806a6C7cbC77411cF7a9042Ae";

    const twitter = 'https://twitter.com/zhangweiwu 205521676';
    const attestorAddress = '0x5f7bFe752Ac1a45F67497d9dCDD9BbDA50A83955';
    const subjectAddress = '0x7a181cb7250776E16783f9d3c9166de0f95AB283';

    const ganacheChainId = 31337;

    // 5 tokens attestation
    const universalIdAttestation     = '0x3082026a30820217308201c4a003020113020101300906072a8648ce3d040230193117301506035504030c0e6174746573746174696f6e2e69643022180f32303231303932353030333133365a180f39393939313233313132353935395a30393137303506092b06010401817a01390c2868747470733a2f2f747769747465722e636f6d2f7a68616e67776569777520323035353231363736308201333081ec06072a8648ce3d02013081e0020101302c06072a8648ce3d0101022100fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f3044042000000000000000000000000000000000000000000000000000000000000000000420000000000000000000000000000000000000000000000000000000000000000704410479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8022100fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd036414102010103420004950c7c0bed23c3cac5cc31bbb9aad9bb5532387882670ac2b1cdf0799ab0ebc764c267f704e8fdda0796ab8397a4d2101024d24c4efff695b3a417f2ed0e48cd300906072a8648ce3d040203420011a20870024a7c268ba55befb481737256b9ecb7697635f507e0f2e8f47137a923e0a819928101b0d2ce4fe42670503a8b522ab8f44f1da9a39f4888b7e8e0bf1c300906072a8648ce3d04020342005bdcc88c8d8908a5b60cb34e441f289c4d4291330e14068ed7808b40e13e187b4081c0f211aebe25b72efce5df2bfa7c8a3921a4ba500a51c6bba50a6731b0391c';
    // Fake: Identifier has a different ID number, but correct signature (Twitter imposter)
    const fakeUniversalIdAttestation = '0x3082026a30820217308201c4a003020113020101300906072a8648ce3d040230193117301506035504030c0e6174746573746174696f6e2e69643022180f32303231303932363031333732375a180f39393939313233313132353935395a30393137303506092b06010401817a01390c2868747470733a2f2f747769747465722e636f6d2f7a68616e67776569777520323035353231363737308201333081ec06072a8648ce3d02013081e0020101302c06072a8648ce3d0101022100fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f3044042000000000000000000000000000000000000000000000000000000000000000000420000000000000000000000000000000000000000000000000000000000000000704410479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8022100fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd036414102010103420004950c7c0bed23c3cac5cc31bbb9aad9bb5532387882670ac2b1cdf0799ab0ebc764c267f704e8fdda0796ab8397a4d2101024d24c4efff695b3a417f2ed0e48cd300906072a8648ce3d040203420066dd0460a8920709cabc1003c934d5fdfed47af4dd03b51dbaed878093152c9a4335f1eafdc7f2d20fe8dd15dcd08df8a9cc1ca76526a0e2450d80c233cbf36b1c300906072a8648ce3d04020342004ce8adbd9a9a338cd941d8838b68fbea154e02471cfe92a3a0c5559c104275be16841e4fdecff47707b9904866f78ef0d2786d51c7f3894fbbbf7c77b346cb6c1c';
    const attestationSubjectPrivateKey = '0x3C19FF5D453C7891EDB92FE70662D5E45AEF658E9F38DF9B0483F6AE2D8DE66E';
    const anyPrivateKey = '0x2222222222222222222222222222222222222222222222222222222222222222';
    const anyPrivateKey2 = '0x2222222222222222222222222222222222222222222222222222222222222666';

    function calcContractAddress(sender: SignerWithAddress, nonce: number)
    {
        const rlp = require('rlp');
        const keccak = require('keccak');

        var input_arr = [ sender.address, nonce ];
        var rlp_encoded = rlp.encode(input_arr);

        var contract_address_long = keccak('keccak256').update(rlp_encoded).digest('hex');

        var contract_address = contract_address_long.substring(24); //Trim the first 24 characters.
        return "0x" + contract_address;
    }


    it("deploy contracts", async function(){
        [owner, addr1, addr2] = await ethers.getSigners();

        testAddr = new ethers.Wallet(anyPrivateKey, owner.provider);
        testAddr2 = new ethers.Wallet(attestationSubjectPrivateKey, owner.provider); //testAddr2 address is subjectAddress
        deployAddr = new ethers.Wallet(anyPrivateKey2, owner.provider);

        const VerifyAttestation = await ethers.getContractFactory("VerifyAttestation");
        verifyAttestation = await VerifyAttestation.deploy();
        await verifyAttestation.deployed();

        await addr1.sendTransaction({
            to: deployAddr.address,
            value: ethers.utils.parseEther("1.0")
        });

        const TwitterTipper = await ethers.getContractFactory("TipOffer");
        proxyTipper = await upgrades.deployProxy(TwitterTipper,[verifyAttestation.address] ,{ kind: 'uups' });
        await proxyTipper.deployed();

        console.log("Addr: " + proxyTipper.address);
        console.log("Owner: " + owner.address);

        let tt = await proxyTipper.getAdmin();
        console.log("admin: " + tt);

        tt = await proxyTipper.getTipFeeFactor();
        console.log("Fee: " + tt);

        const StableCoin = await ethers.getContractFactory("StableCoin");
        stableCoin = await StableCoin.deploy();
        await stableCoin.deployed();

        //let ethBal :BigNumber = await ethers.provider.getBalance(testAddr.address);
        let totalBalance :BigNumber = await stableCoin.totalSupply();
        let name = await stableCoin.name();
        let symbol = await stableCoin.symbol();
        let stableCoinOwner = await stableCoin.owner();

        console.log(name + " has " + totalBalance + " " + symbol + "(" + stableCoinOwner + ")");
        console.log(name + " (" + stableCoin.address + ")" );

        //set contract to use debug attestation key
        proxyTipper.setAttestor(attestorAddress);

    })

    it("Commit Tip and claim", async function(){
        // create a tip transaction and try to claim it
        {
            // move some Ether to test account
            await addr1.sendTransaction({
                to: testAddr.address,
                value: ethers.utils.parseEther("50.0")
            });

            var testAddrBal = await ethers.provider.getBalance(testAddr.address);
            console.log("TestAddr Bal: " + testAddrBal);

            let tx;
            const transactionData = await proxyTipper.connect(testAddr).createTip([], twitter, {
                value: ethers.utils.parseEther("0.1"),
            });

            console.log("OUT: " + transactionData.data );

            let estimatedGasCommit = await proxyTipper.connect(testAddr).estimateGas.createTip([], twitter, {
                value: ethers.utils.parseEther("0.1"),
            });
            console.log('estimatedGasCommit = ' + estimatedGasCommit );

            //show testAddr balance
            testAddrBal = await ethers.provider.getBalance(testAddr.address);
            console.log("TestAddr Bal: " + testAddrBal);

            testAddrBal = await ethers.provider.getBalance(subjectAddress);
            console.log("Subject Bal: " + testAddrBal);

            //dump the tip
            await showTipFromId(1);
            await showTipFromId(2);

            //now claim this tip
            const transactionData2 = await proxyTipper.connect(deployAddr).collectTip([1], universalIdAttestation);

            await showTipFromId(1);
            testAddrBal = await ethers.provider.getBalance(subjectAddress);
            console.log("Subject Bal: " + testAddrBal);

            //now create a tip with ETH and ERC20
            // move some Stablecoin to test account
            await stableCoin.connect(owner).transfer(testAddr.address, ethers.utils.parseEther("50.0"));
            let testBal :BigNumber = await stableCoin.connect(testAddr).balanceOf(testAddr.address);
            console.log("TestBal Stablecoin: " + testBal);

            //call approve
            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("1.3"));

            //make commitment
            let erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("1.3"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("0.2"),
            });

            console.log("OUT: " + erc20TipTx.data );

            //check balance of ERC20
            testBal = await stableCoin.connect(testAddr).balanceOf(testAddr.address);
            console.log("TestBal Stablecoin: " + testBal);

            await showTipFromId(2);

            testAddrBal = await stableCoin.connect(testAddr).balanceOf(subjectAddress);
            console.log("StableCoin Subject Bal: " + testAddrBal);

            testAddrBal = await stableCoin.connect(testAddr).balanceOf(proxyTipper.address);
            console.log("StableCoin Contract Bal: " + testAddrBal);

            //attempt to claim tip by imposter
            await expect(proxyTipper.connect(addr1).collectTip([2], fakeUniversalIdAttestation)).to.be.revertedWith('Invalid Attestation used');

            //create another commitment
            //call approve
            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("4.1"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("4.1"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("1.1"),
            });

            await showTips([1, 2, 3]);

            //claim tip
            const transactionData3 = await proxyTipper.connect(addr1).collectTip([2,3], universalIdAttestation); //show check

            //check new balance
            testAddrBal = await ethers.provider.getBalance(subjectAddress);
            console.log("Subject Bal: " + testAddrBal);

            testAddrBal = await stableCoin.connect(testAddr).balanceOf(subjectAddress);
            console.log("StableCoin Subject Bal: " + testAddrBal);

            //Now try to claim tips which have one that's for someone else
            //setup tips 4,5 and 6 (6 is for a different user)
            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("4.1"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("4.1"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("1.1"),
            });

            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("6.2"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("6.2"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("3.0"),
            });

            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("3.2"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("3.2"), "0x00"]], "hekatonchires 777", {
                value: ethers.utils.parseEther("3.0"),
            });

            //now try to claim all under the main attestation
            await expect(proxyTipper.connect(addr1).collectTip([4,5,6], universalIdAttestation)).to.be.revertedWith('Not your tip');

            //add another tip
            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("2.2"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("2.2"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("0.01"),
            });

            await showTips([4, 5, 6, 7]);

            //finally claim all the tips we are allowed to, using a third payer address (testAddr2)
            const transactionData4 = await proxyTipper.connect(deployAddr).collectTip([4,5,7], universalIdAttestation);

            //check new balance
            testAddrBal = await ethers.provider.getBalance(subjectAddress);
            console.log("Subject Bal: " + testAddrBal);

            let testStableBal = await stableCoin.connect(testAddr).balanceOf(subjectAddress);
            console.log("StableCoin Subject Bal: " + testStableBal);

            await showTips([4, 5, 6, 7]);

            //ensure balance is correct
            //Subject Bal: 5510000000000000000
            //StableCoin Subject Bal: 17900000000000000000
                                           
            expect(testAddrBal).to.be.equal("5510000000000000000");
            expect(testStableBal).to.be.equal("17900000000000000000");
        }
    });

    it("Redeploy and test another tip claim", async function(){
        // create a tip transaction and try to claim it
        {
            //deploy new logic and upgrade
            const TwitterTipper = await ethers.getContractFactory("TipOffer");

            const newProxyTipper = await upgrades.upgradeProxy(proxyTipper.address, TwitterTipper);
            await newProxyTipper.deployed();

            //create some tips
            //Now try to claim tips which have one that's for someone else
            //setup tips 4,5 and 6 (6 is for a different user)
            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("4.1"));
            let erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("4.1"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("1.1"),
            });

            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("6.2"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("6.2"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("3.0"),
            });

            //add another tip
            await stableCoin.connect(testAddr).approve(proxyTipper.address, ethers.utils.parseEther("2.2"));
            erc20TipTx = await proxyTipper.connect(testAddr).createTip([[stableCoin.address, ethers.utils.parseEther("2.2"), "0x00"]], twitter, {
                value: ethers.utils.parseEther("0.01"),
            });

            await showTips([8, 9, 10]);

            //finally claim all the tips we are allowed to
            const transactionData4 = await proxyTipper.connect(addr1).collectTip([8,9,10], universalIdAttestation);

            //check new balance
            var testAddrBal = await ethers.provider.getBalance(subjectAddress);
            console.log("Subject Bal: " + testAddrBal);

            testAddrBal = await stableCoin.connect(testAddr).balanceOf(subjectAddress);
            console.log("StableCoin Subject Bal: " + testAddrBal);

            await showTips([8, 9, 10]);
        }
    });

    async function showTipFromId(tipId: number)
    {
        console.log("-----");
        console.log("Tip commit ID: " + tipId);
        let tip = await proxyTipper.getTip(tipId);

        //(PaymentToken[] memory paymentTokens, address offerer, uint256 weiValue, string memory identifier, bool completed)
        console.log("Offerer/owner: " + tip.offerer);
        console.log("Identifier: " + tip.identifier);
        console.log("Addr payee: " + tip.payee);
        console.log("Amount: " + tip.weiValue);

        for (let i = 0; i < tip.paymentTokens.length; i++) {
            let erc20Address = tip.paymentTokens[i].erc20;
            let amount :BigNumber = tip.paymentTokens[i].amount;
            console.log("ERC20 Token: " + erc20Address + " (" + amount + ")");
        } 
        console.log("-----");
    }

    async function showTips(tipId: number[])
    {
        console.log("-----");
        console.log("Batch Query Tips: " + tipId);
        let tips = await proxyTipper.getTips(tipId);

        for (let i = 0; i < tips.length; i++) {
            //(PaymentToken[] memory paymentTokens, address offerer, uint256 weiValue, string memory identifier, bool completed)
            let thisTip = tips[i];
            console.log("tipId: " + tipId[i]);
            console.log("Amount: " + thisTip.weiValue);
            console.log("Completed: " + thisTip.completed);

            for (let i = 0; i < thisTip.paymentTokens.length; i++) {
                let erc20Address = thisTip.paymentTokens[i].erc20;
                let amount :BigNumber = thisTip.paymentTokens[i].amount;
                console.log("ERC20 Token: " + erc20Address + " (" + amount + ")");
            } 
        }

        console.log("-----");
    }
});
