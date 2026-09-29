const webpack = require("webpack");
const HtmlWebPackPlugin = require("html-webpack-plugin");
const { merge } = require("webpack-merge");
const baseConfig = require("./webpack.config.base");
const dotenv = require('dotenv');
const path = require('path');
const fs = require("fs");
const { clientEnv } = require("./webpack.env");

dotenv.config({path: './.env.development'})

module.exports = merge(baseConfig, {
  mode: "development",

  devServer: {     
    //port: 443,    
    port: 3000,                                 
    server : {
      // type : 'https',
      // options: {        
      //   key: fs.readFileSync(path.resolve(__dirname, 'pem')),
      //   cert: fs.readFileSync(path.resolve(__dirname, 'pem')),                
      // },    
      
    },
    static: {
      directory: path.join(__dirname, 'public'),
    },
    open: true,
    historyApiFallback: true,
    compress: false,
    hot: true,
    liveReload: false,     
  },

  plugins: [
    new HtmlWebPackPlugin({
      template: './public/index.html'
    }),     
    // prod 와 같은 규칙 — REACT_APP_* 전부 (webpack.env.js).
    new webpack.DefinePlugin(clientEnv("development")),
  ],
});