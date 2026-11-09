package com.zebrunner.mcloud.grid.util;

import org.testng.Assert;
import org.testng.annotations.Test;

public class NodeTokenTest {
    private static final String SECRET = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
    private static final String TOKEN = "mcn1.node-1." + SECRET;

    @Test
    public void tokenOfTheFormatIsParsed() {
        NodeToken token = NodeToken.parse(TOKEN).orElseThrow();

        Assert.assertEquals(token.getLabel(), "node-1");
        Assert.assertEquals(token.getValue(), TOKEN);
        // sha256 hex, the form STF keeps
        Assert.assertEquals(token.getHash(), NodeToken.sha256Hex(TOKEN));
        Assert.assertEquals(token.getHash().length(), 64);
        Assert.assertTrue(token.getHash().matches("[0-9a-f]{64}"), token.getHash());
        Assert.assertEquals(NodeToken.parse(TOKEN).orElseThrow(), token);
        Assert.assertEquals(NodeToken.parse(TOKEN).orElseThrow().hashCode(), token.hashCode());
        Assert.assertNotEquals(NodeToken.parse("mcn1.node-2." + SECRET).orElseThrow(), token);
    }

    @Test
    public void knownSha256() {
        Assert.assertEquals(NodeToken.sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    public void tokenIsNotShown() {
        Assert.assertEquals(NodeToken.parse(TOKEN).orElseThrow().toString(), "mcn1.node-1.*****");
    }

    @Test
    public void malformedTokensAreNotParsed() {
        String[] malformed = {
            null, "", "mcn1.", "mcn1.node-1", "mcn1.node-1.", "mcn1.node-1." + SECRET.substring(1), "mcn1.node-1." + SECRET + "x",
            "mcn1.Node-1." + SECRET, "mcn1.-node." + SECRET, "mcn1.node_1." + SECRET, "mcn1." + "a".repeat(41) + "." + SECRET,
            "mcn1.node-1." + SECRET.replace('A', '+'), "mcn2.node-1." + SECRET, " " + TOKEN, "mcn1.node.1." + SECRET,
        };
        for (String token : malformed) {
            Assert.assertTrue(NodeToken.parse(token).isEmpty(), String.valueOf(token));
        }
        Assert.assertTrue(NodeToken.parse("mcn1." + "a".repeat(40) + "." + SECRET).isPresent());
        Assert.assertTrue(NodeToken.parse("mcn1.0." + SECRET).isPresent());
    }

    @Test
    public void prefixMarksANodeToken() {
        Assert.assertTrue(NodeToken.hasNodeTokenPrefix(TOKEN));
        Assert.assertTrue(NodeToken.hasNodeTokenPrefix("mcn1.broken"));
        Assert.assertFalse(NodeToken.hasNodeTokenPrefix("0123abcd"));
        Assert.assertFalse(NodeToken.hasNodeTokenPrefix(null));
    }
}
