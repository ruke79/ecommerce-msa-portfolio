module.exports = {
	presets : [
		'@babel/preset-env',
		// webpack(webpack.config.base.js) 과 같은 runtime 을 쓴다. classic 으로 두면
		// jest 에서만 파일마다 `import React` 가 필요해져 빌드와 테스트의 규칙이 달라진다.
		['@babel/preset-react', { runtime: 'automatic' }],
	],
}
