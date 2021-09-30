// SPDX-License-Identifier: MIT
/* Twitter Tipping contract */
/* AlphaWallet 2021 */

pragma solidity ^0.8.4;
pragma experimental ABIEncoderV2; 

import "hardhat/console.sol";
import "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/utils/Strings.sol";
import "./interface/IVerifyAttestation.sol";
import "./AddressUtil.sol";

contract TipOfferData {
    struct PaymentToken {
        address erc20; // Ether itself is allowed in the case of return value
        uint256 amount;
        bytes auth; // authorisation; null if underlying contract doesn't support it
    }

    struct Tip {
        PaymentToken[] paymentTokens;
        uint256         amtPayable; // any amtPayable commitment's amount
        string identifier;          // Twitter ID of tipee
        address payable adrPayee;   // record address of tipee
        address offerer;            // only need this if offer is recindable
    }

    struct TipQuery {
        PaymentToken[] paymentTokens;
        uint256 weiValue;
        bool completed; // authorisation; null if underlying contract doesn't support it
    }

    address _verifyAttestation; //this points to the contract that verifies attestations: VerifyAttestation.sol

    // Dynamic data

    // Mapping commitmentId to NFT sets
    mapping (uint256 => Tip) _tips;

    uint256 _tipId;
    uint256 _tipFeePercentage; // controllable from 0 to 10 000 where 10 000 means 100%

    // Static data
    address _attestorAddress; //Attestor key
}

contract TipOffer is TipOfferData {

    address payable owner;

    using Strings for uint256;
    using SafeERC20 for IERC20;

    // this error shouldn't happen and should be monitored. Normally,
    // committed cryptocurrencies are payed out through a formula which
    // a user or a dapp shouldn't be able to affect, therefore if this
    // error occurs, either the smart contract has already lost money
    // unaccounted for, or an attack was attempted
    error InsufficientBalance(uint256 tipId);
    error CommitmentPayoutFailed(uint256 tipId);
    error CommissionPayoutFailed(uint256 tipId);
    error CallerNotAuthorised();
    error PayingOutBeforeOfferTaken();

    modifier onlyOwner() {
        require(owner == msg.sender, "Ownable: caller is not the owner");
        _;
    }

    bytes constant emptyBytes = new bytes(0x00);

    constructor(address verificationContract) 
    {
        owner = payable(msg.sender);
        _tipId = 1; //set once, can never be set again (contract updates this value)
        _tipFeePercentage = 100;
        _attestorAddress = 0x5f7bFe752Ac1a45F67497d9dCDD9BbDA50A83955;
        _verifyAttestation = verificationContract;
    }

    // Required for updating the verification contract address and commission
    function reconfigure(address verificationContract, uint256 commission) external onlyOwner
    {
        require(AddressUtil.isContract(verificationContract), "Address must be a contract");
        require(commission<=10000, "Commission limits 0..10000(100%)");
        _verifyAttestation = verificationContract;
        _tipFeePercentage = commission;
    }

    function setAttestor(address attestorAddress) external onlyOwner
    {
        _attestorAddress = attestorAddress;
    }

    event CreateTip(address indexed offerer, string indexed identifier, uint256 indexed tipId);
    event CollectTips(string indexed identifier, address tipee);
    event CancelTip(address indexed offerer, string indexed identifier, uint256 indexed tipId);

    function getAdmin() external view returns(address) {
        return owner;
    }

    function createTip1(string memory identifier) public payable
    {
        PaymentToken[] memory noTokens;
        return createTip(noTokens, identifier);
    }
    
    /****
     * 
     * @param paymentTokens: ERC20 tokens to be committed
     * @param identifier: the identifier of the entity who can receive the tip
     */ 
    function createTip(PaymentToken[] memory paymentTokens, string memory identifier) public payable
    {
        require ((paymentTokens.length > 0 || msg.value > 0), "Tip requires base value or ERC20 payment");

        _tips[_tipId].identifier = identifier;
        _tips[_tipId].adrPayee  = payable(0); // null
        _tips[_tipId].amtPayable = msg.value;
        _tips[_tipId].offerer = msg.sender;
        
        //Would a variant that does transfer of ERC20 at 'collectTip' be better? Same gas, but can fail if ERC20 amount isn't sufficient
        //Could lead to tip trolling
        for (uint256 index = 0; index < paymentTokens.length; index++)
        {
            _tips[_tipId].paymentTokens.push(paymentTokens[index]);
            IERC20 paymentTokenContract = IERC20(paymentTokens[index].erc20);
            paymentTokenContract.safeTransferFrom(msg.sender, address(this), paymentTokens[index].amount); //can use safeTranser (vs safeTransferFrom) because createTip can only be called by owner
        }
        
        emit CreateTip(msg.sender, identifier, _tipId);
        _tipId++;
    }

    function checkIdentifier(string memory identifierId, string memory checkId) internal pure returns(bool)
    {
        return (keccak256(abi.encodePacked((identifierId))) == 
                    keccak256(abi.encodePacked((checkId))));
    }

    /****
     * collectTip() checks the crypto and delivers the tip
     * 
     * Caller: It's designed to be called by either the dvp contract or by King Midas
     *
     * tipId: the id of the tip: note that attestation should not contain the tipId for convenience - after creating the first attestation, providing the tip recipient
     *                           wishes the subsequent tips to be paid to the same address there's no need to create a new attestation, which streamlines collecting tips.
     * tipAttestation: the tip attestation 
     */
    function collectTip(uint256[] memory tipIds, bytes memory coSignedAttestation) external 
    {
        //recover the commitment ID
        address payable subjectAddress;
        bool passedVerification;
        require(tipIds.length > 0, "No TipIds");
        string memory identifier = _tips[tipIds[0]].identifier;

        IVerifyAttestation verifier = IVerifyAttestation(_verifyAttestation);
        (passedVerification, subjectAddress) = verifier.checkAttestationValidity(coSignedAttestation, identifier,  _attestorAddress, msg.sender);
        require(passedVerification, "Invalid Attestation used");

        for (uint256 index = 0; index < tipIds.length; index++)
        {
            uint256 tipId = tipIds[index];
            require(_tips[tipId].adrPayee == address(0), "Tip already collected");
            if (index > 0) { require(checkIdentifier(identifier, _tips[tipId].identifier), "Not your tip"); }
            _tips[tipId].adrPayee = subjectAddress;
            pay(tipId, subjectAddress, _tipFeePercentage);
        }

        emit CollectTips(_tips[0].identifier, subjectAddress);
    }

    function pay(uint256 tipId, address payable beneficiary, uint256 commissionMultiplier) internal
    {
        uint256 ethValue = _tips[tipId].amtPayable;
        _tips[tipId].amtPayable = 0; //prevent re-entrancy; if we hit a revert this is unwound
        
        if (_tips[tipId].adrPayee == address(0)) {
            revert PayingOutBeforeOfferTaken();
        }

        bool paymentSuccessful;
        if (ethValue > 0)
        {
            uint256 commissionWei = (ethValue * commissionMultiplier)/10000;

            if (commissionWei > 0)
            {
                (paymentSuccessful, ) = owner.call{value: commissionWei}(""); //commission
                if (!paymentSuccessful) {
                    revert CommissionPayoutFailed(tipId);
                }
            }

            (paymentSuccessful, ) =  beneficiary.call{value: (ethValue - commissionWei)}(""); //payment to signer
            if (!paymentSuccessful) {
                revert CommitmentPayoutFailed(tipId);
            }
        }
        
        // Transfer ERC20 payments - these will have been stored within this contract, and are moving to the payee
        // Note that this may change in future, we may want a single move from token owner's account to the payee
        for (uint256 index = 0; index < _tips[tipId].paymentTokens.length; index++)
        {
            IERC20 tokenContract = IERC20(_tips[tipId].paymentTokens[index].erc20);
            uint256 transferVal = _tips[tipId].paymentTokens[index].amount;
            _tips[tipId].paymentTokens[index].amount = 0; //zeroise to avoid re-entrancy attacks
            if (commissionMultiplier > 0)
            {
                uint256 commissionVal = (transferVal * commissionMultiplier)/10000;
                transferVal = transferVal - commissionVal;
                tokenContract.safeTransfer(owner, commissionVal);
            }
            
            tokenContract.safeTransfer(beneficiary, transferVal);
        }
    }

    // Fetch details of a specific commitment
    function getTip(uint256 tipId) external view 
        returns (PaymentToken[] memory paymentTokens, address offerer, uint256 weiValue, string memory identifier, address payee, bool completed)
    {
        Tip memory tip = _tips[tipId];
        paymentTokens = tip.paymentTokens;
        
        identifier = tip.identifier;
        weiValue = tip.amtPayable;
        offerer = tip.offerer;
        payee = tip.adrPayee;
        completed = (tip.adrPayee != address(0));
    }

    function getTips(uint256[] memory tipIds) external view 
        returns (TipQuery[] memory tips)
    {
        tips = new TipQuery[](tipIds.length);
        for (uint256 index = 0; index < tipIds.length; index++)
        {
            Tip memory tip = _tips[tipIds[index]];
            tips[index].paymentTokens = tip.paymentTokens;
            tips[index].weiValue = tip.amtPayable;
            tips[index].completed = (tip.adrPayee != address(0));
        }
    }
    
    // Need to implement this to receive ERC721 Tokens
    function onERC721Received(address, address, uint256, bytes calldata) external pure returns(bytes4) 
    {
        return bytes4(keccak256("onERC721Received(address,address,uint256,bytes)"));
    } 
    
    function getTipFeeFactor() external view returns(uint256)
    {
        return _tipFeePercentage;
    }

    function cancelTip(uint256 tipId) external payable
    {
        require(msg.sender == _tips[tipId].offerer, "Must be tip owner");

        pay(tipId, payable(msg.sender), 0);

        //emit event to aid bookkeeping
        emit CancelTip(_tips[tipId].offerer, _tips[tipId].identifier, tipId);
        delete (_tips[tipId]);
    }

    /*************************************
    * External helper functions
    **************************************/

    function verifyAttestation(bytes memory attestation) external view returns(string memory, address, address)
    {
        IVerifyAttestation verifier = IVerifyAttestation(_verifyAttestation);
        return verifier.verifyIDAttestation(attestation);
    }

    function getAttestationTimestamp(bytes memory attestation) external view returns(string memory startTime, string memory endTime)
    {
        IVerifyAttestation verifier = IVerifyAttestation(_verifyAttestation);
        return verifier.getNFTAttestationTimestamp(attestation);
    }

    function isContract(address account) internal view returns (bool) {
        // This method relies on extcodesize, which returns 0 for contracts in
        // construction, since the code is only stored at the end of the
        // constructor execution.

        uint256 size;
        assembly {
            size := extcodesize(account)
        }
        return size > 0;
    }
}
