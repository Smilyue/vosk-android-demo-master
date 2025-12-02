package org.vosk.speechtest;

import java.util.HashMap;
import java.util.Map;
    class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        boolean isEndOfWord = false;
        String packet;
    }

    public class Trie {
        private TrieNode root;

        public Trie() {
            root = new TrieNode();
        }
        public void clear() {
            root = new TrieNode();
        }
        // 插入關鍵字
        public void insert(String keyword, String value) {
            TrieNode node = root;
            for (char ch : keyword.toCharArray()) {
                node.children.putIfAbsent(ch, new TrieNode());
                node = node.children.get(ch);
            }
            node.isEndOfWord = true;
            node.packet = value;
        }

        // 搜尋關鍵字並獲取對應值
        public String search(String keyword) {
            TrieNode node = root;
            for (char ch : keyword.toCharArray()) {
                if (!node.children.containsKey(ch)) {
                    return null;
                }
                node = node.children.get(ch);
            }
            return node.isEndOfWord ? node.packet : null;
        }

        // 搜尋是否存在關鍵字
        public boolean contains(String keyword) {
            return search(keyword) != null;
        }
    }

