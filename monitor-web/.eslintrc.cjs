module.exports = {
  root: true,
  env: {
    browser: true,
    es2022: true,
    node: true,
    'vue/setup-compiler-macros': true
  },
  extends: ['eslint:recommended', 'plugin:vue/vue3-essential', 'prettier'],
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module'
  },
  rules: {
    'no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    'no-console': 'off',
    'no-debugger': process.env.NODE_ENV === 'production' ? 'error' : 'warn',
    'vue/multi-word-component-names': 'off'
  }
}
